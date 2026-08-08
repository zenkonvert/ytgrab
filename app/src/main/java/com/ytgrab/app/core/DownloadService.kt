package com.ytgrab.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ytgrab.app.MainActivity
import com.ytgrab.app.R
import com.ytgrab.app.YtGrabApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps yt-dlp running as a foreground service so Android doesn't kill the process
 * when the user backgrounds the app during a long download.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var binaryManager: BinaryManager
    private lateinit var engine: DownloadEngine

    override fun onCreate() {
        super.onCreate()
        binaryManager = BinaryManager(applicationContext)
        engine = DownloadEngine(applicationContext, binaryManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val kind = MediaKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: MediaKind.AUDIO.name)
        val audioFormat = AudioFormat.valueOf(intent.getStringExtra(EXTRA_AUDIO_FORMAT) ?: AudioFormat.MP3.name)
        val videoQuality = VideoQuality.valueOf(intent.getStringExtra(EXTRA_VIDEO_QUALITY) ?: VideoQuality.P1080.name)

        startForeground(NOTIF_ID, buildNotification("Starting download…", 0))

        scope.launch {
            engine.download(url, kind, audioFormat, videoQuality).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        updateNotification("Downloading… ${event.speedText} • ETA ${event.etaText}", event.percent.toInt())
                    }
                    is DownloadEvent.Done -> {
                        updateNotification("Download complete", 100, ongoing = false)
                        stopSelf(startId)
                    }
                    is DownloadEvent.Failed -> {
                        updateNotification("Failed: ${event.message.take(80)}", 0, ongoing = false)
                        stopSelf(startId)
                    }
                    is DownloadEvent.LogLine -> { /* verbose, ignore in notification */ }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean = true): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YtGrabApp.CHANNEL_ID)
            .setContentTitle("YTGrab")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0 && ongoing)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int, ongoing: Boolean = true) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text, progress, ongoing))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val NOTIF_ID = 1001
        const val EXTRA_URL = "extra_url"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_AUDIO_FORMAT = "extra_audio_format"
        const val EXTRA_VIDEO_QUALITY = "extra_video_quality"
    }
}
