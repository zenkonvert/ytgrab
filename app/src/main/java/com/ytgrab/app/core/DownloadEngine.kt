package com.ytgrab.app.core

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

enum class MediaKind { AUDIO, VIDEO }
enum class AudioFormat(val ext: String) { MP3("mp3"), FLAC("flac") }
enum class VideoQuality(val heightCap: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p")
}

sealed class DownloadEvent {
    data class Progress(val percent: Float, val etaText: String, val speedText: String) : DownloadEvent()
    data class LogLine(val line: String) : DownloadEvent()
    data class Done(val outputPath: String) : DownloadEvent()
    data class Failed(val message: String) : DownloadEvent()
}

class DownloadEngine(
    private val context: Context,
    private val binaryManager: BinaryManager
) {

    /**
     * Runs yt-dlp for the given request and emits progress events.
     * Files are written to the public Music or Movies/Downloads folder via legacy public
     * dirs (works across API 24 - 35; on API 29+ MediaStore will also index them once
     * yt-dlp writes to the public path, since we scan the file afterward).
     */
    fun download(
        url: String,
        kind: MediaKind,
        audioFormat: AudioFormat = AudioFormat.MP3,
        videoQuality: VideoQuality = VideoQuality.P1080
    ): Flow<DownloadEvent> = flow {
        val ytDlp = binaryManager.ytDlpFile
        val ffmpeg = binaryManager.ffmpegFile

        if (!ytDlp.exists() || !ffmpeg.exists()) {
            emit(DownloadEvent.Failed("Required binaries are not ready yet. Please wait a moment and retry."))
            return@flow
        }

        val outputDir = resolveOutputDir(kind)
        outputDir.mkdirs()

        val outputTemplate = File(outputDir, "%(title).150B [%(id)s].%(ext)s").absolutePath

        val args = mutableListOf(
            ytDlp.absolutePath,
            "--no-mtime",
            "--ffmpeg-location", ffmpeg.parentFile!!.absolutePath,
            "-o", outputTemplate,
            "--newline",                 // one progress line per update, easy to parse
            "--progress-template", "download:%(progress._percent_str)s|%(progress._eta_str)s|%(progress._speed_str)s"
        )

        when (kind) {
            MediaKind.AUDIO -> {
                args += listOf(
                    "-x",
                    "--audio-format", audioFormat.ext,
                    "--audio-quality", "0" // best
                )
            }
            MediaKind.VIDEO -> {
                val h = videoQuality.heightCap
                args += listOf(
                    "-f", "bestvideo[height<=$h]+bestaudio/best[height<=$h]",
                    "--merge-output-format", "mp4"
                )
            }
        }

        args += url

        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(outputDir)
                .start()
        }

        var finalPath: String? = null
        var failMessage: String? = null

        withContext(Dispatchers.IO) {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue

                    when {
                        text.startsWith("download:") -> {
                            val payload = text.removePrefix("download:")
                            val parts = payload.split("|")
                            val percentStr = parts.getOrNull(0)?.trim()?.removeSuffix("%")
                            val eta = parts.getOrNull(1)?.trim() ?: ""
                            val speed = parts.getOrNull(2)?.trim() ?: ""
                            val percent = percentStr?.toFloatOrNull() ?: 0f
                            emit(DownloadEvent.Progress(percent, eta, speed))
                        }
                        text.contains("[Merger] Merging formats into") ||
                            text.contains("[ExtractAudio] Destination:") ||
                            text.contains("[Metadata] Adding metadata to") -> {
                            // Try to pull the final path out of these lines
                            val idx = text.indexOf("\"")
                            if (idx >= 0) {
                                finalPath = text.substringAfter("Destination: ").trim()
                                    .ifBlank { text.substringAfter("into ").trim() }
                            }
                            emit(DownloadEvent.LogLine(text))
                        }
                        text.startsWith("ERROR:") -> {
                            failMessage = text
                            emit(DownloadEvent.LogLine(text))
                        }
                        else -> emit(DownloadEvent.LogLine(text))
                    }
                }
            }
            process.waitFor()
        }

        if (failMessage != null) {
            emit(DownloadEvent.Failed(failMessage!!))
        } else {
            // Fall back: pick the newest file in the output dir if we couldn't parse a path
            val resolved = finalPath ?: outputDir.listFiles()
                ?.filter { it.isFile }
                ?.maxByOrNull { it.lastModified() }
                ?.absolutePath
                ?: outputDir.absolutePath

            MediaScanner.scan(context, resolved)
            emit(DownloadEvent.Done(resolved))
        }
    }

    private fun resolveOutputDir(kind: MediaKind): File {
        val publicDir = when (kind) {
            MediaKind.AUDIO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            MediaKind.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        return File(publicDir, "YTGrab")
    }
}
