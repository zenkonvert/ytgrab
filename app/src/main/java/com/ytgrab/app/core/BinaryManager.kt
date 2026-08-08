package com.ytgrab.app.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Manages the two native binaries the app depends on:
 *
 *  - ffmpeg/ffprobe: bundled inside the APK (jniLibs) at build time. FFmpeg's CLI and core
 *    encoders essentially never break, so it does not need runtime updates. Bundling avoids
 *    ever depending on a third-party binary-hosting URL staying alive.
 *
 *  - yt-dlp: downloaded and self-updated at runtime from yt-dlp's official GitHub releases.
 *    This is the piece that actually needs to stay current, since YouTube changes break
 *    extraction regularly and yt-dlp ships fixes frequently. Official standalone Linux ARM
 *    builds (yt-dlp_linux_aarch64 / yt-dlp_linux_armv7l) are self-contained ELF executables
 *    with no Python dependency, so they run fine under Android's Linux kernel.
 */
class BinaryManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("binary_manager", Context.MODE_PRIVATE)

    private val binDir: File by lazy {
        File(context.filesDir, "bin").apply { mkdirs() }
    }

    val ytDlpFile: File get() = File(binDir, "yt-dlp")
    val ffmpegFile: File get() = File(binDir, "ffmpeg")
    val ffprobeFile: File get() = File(binDir, "ffprobe")

    private val abi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: Build.SUPPORTED_ABIS.first()

    /**
     * Call once at app startup (and optionally on a periodic WorkManager job).
     * Ensures ffmpeg/ffprobe are extracted from jniLibs, and that yt-dlp exists,
     * updating it in the background if a newer release is available.
     */
    suspend fun ensureReady(onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        ensureFfmpegExtracted(onStatus)
        ensureYtDlpPresent(onStatus)
    }

    // ---------------- ffmpeg (bundled, static) ----------------

    private fun ensureFfmpegExtracted(onStatus: (String) -> Unit) {
        // Android's PackageManager already places jniLibs at nativeLibraryDir with exec permission.
        // We reference them directly rather than copying, since Android already makes them
        // executable at install time (and re-verifies on every app update).
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val bundledFfmpeg = File(nativeDir, "libffmpeg_bin.so")   // renamed per Android's .so requirement
        val bundledFfprobe = File(nativeDir, "libffprobe_bin.so")

        if (bundledFfmpeg.exists() && !ffmpegFile.exists()) {
            // Symlink/copy reference so the rest of the app can call a clean "ffmpeg" name.
            bundledFfmpeg.copyTo(ffmpegFile, overwrite = true)
            ffmpegFile.setExecutable(true, false)
        }
        if (bundledFfprobe.exists() && !ffprobeFile.exists()) {
            bundledFfprobe.copyTo(ffprobeFile, overwrite = true)
            ffprobeFile.setExecutable(true, false)
        }
        onStatus("ffmpeg ready")
    }

    // ---------------- yt-dlp (self-updating) ----------------

    private fun ensureYtDlpPresent(onStatus: (String) -> Unit) {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val needsCheck = System.currentTimeMillis() - lastCheck > dayMs

        if (!ytDlpFile.exists()) {
            onStatus("Downloading yt-dlp for the first time…")
            downloadLatestYtDlp()
        } else if (needsCheck) {
            onStatus("Checking for yt-dlp updates…")
            try {
                downloadLatestYtDlp(onlyIfNewer = true)
            } catch (e: Exception) {
                // Non-fatal: keep using the existing binary if the update check fails
                // (e.g. offline). This is why we never HARD depend on network for this step.
                Log.w(TAG, "yt-dlp update check failed, continuing with existing binary", e)
            }
        }
    }

    private fun downloadLatestYtDlp(onlyIfNewer: Boolean = false) {
        val assetName = when (abi) {
            "arm64-v8a" -> "yt-dlp_linux_aarch64"
            "armeabi-v7a" -> "yt-dlp_linux_armv7l"
            else -> "yt-dlp_linux_aarch64"
        }

        val releaseJson = httpGetString(GITHUB_LATEST_RELEASE_API)
        val json = JSONObject(releaseJson)
        val tagName = json.getString("tag_name")

        if (onlyIfNewer && tagName == prefs.getString(KEY_CURRENT_VERSION, null)) {
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            return // already up to date
        }

        val assets = json.getJSONArray("assets")
        var downloadUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == assetName) {
                downloadUrl = asset.getString("browser_download_url")
                break
            }
        }
        requireNotNull(downloadUrl) { "Could not find yt-dlp asset $assetName in latest release" }

        val tmpFile = File(binDir, "yt-dlp.tmp")
        httpDownloadFile(downloadUrl, tmpFile)

        // Basic sanity check: file should be non-trivially sized (a few MB at least)
        if (tmpFile.length() < 1_000_000) {
            tmpFile.delete()
            throw IllegalStateException("Downloaded yt-dlp binary looks truncated/invalid")
        }

        tmpFile.copyTo(ytDlpFile, overwrite = true)
        tmpFile.delete()
        ytDlpFile.setExecutable(true, false)

        prefs.edit()
            .putString(KEY_CURRENT_VERSION, tagName)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()

        Log.i(TAG, "yt-dlp updated to $tagName")
    }

    fun currentYtDlpVersion(): String = prefs.getString(KEY_CURRENT_VERSION, "unknown") ?: "unknown"

    /** Force a manual "Update yt-dlp now" check, e.g. from a Settings button. */
    suspend fun forceUpdateCheck(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        onStatus("Checking for yt-dlp updates…")
        downloadLatestYtDlp(onlyIfNewer = false)
        onStatus("yt-dlp is up to date (${currentYtDlpVersion()})")
    }

    // ---------------- low-level HTTP helpers (no extra dependencies) ----------------

    private fun httpGetString(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        conn.inputStream.use { input ->
            return input.bufferedReader().readText()
        }
    }

    private fun httpDownloadFile(urlStr: String, dest: File) {
        var url = URL(urlStr)
        var conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        // Manually follow redirects across hosts (GitHub release assets redirect to S3)
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            url = URL(next)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            redirects++
        }
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val TAG = "BinaryManager"
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        private const val KEY_LAST_CHECK = "last_update_check"
        private const val KEY_CURRENT_VERSION = "current_ytdlp_version"
    }
}
