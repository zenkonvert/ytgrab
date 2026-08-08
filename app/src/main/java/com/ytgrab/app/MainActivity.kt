package com.ytgrab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ytgrab.app.core.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binaryManager: BinaryManager

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way, downloads still work without notif permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binaryManager = BinaryManager(applicationContext)
        requestNotifPermissionIfNeeded()

        // Handle "Share" intent from YouTube/other apps
        val sharedUrl = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null

        setContent {
            YTGrabTheme {
                AppRoot(binaryManager = binaryManager, prefilledUrl = sharedUrl)
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun YTGrabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}

@Composable
fun AppRoot(binaryManager: BinaryManager, prefilledUrl: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var binariesReady by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Preparing…") }

    LaunchedEffect(Unit) {
        binaryManager.ensureReady { status ->
            statusText = status
        }
        binariesReady = true
        statusText = "Ready"
    }

    var url by remember { mutableStateOf(prefilledUrl ?: "") }
    var kind by remember { mutableStateOf(MediaKind.AUDIO) }
    var audioFormat by remember { mutableStateOf(AudioFormat.MP3) }
    var videoQuality by remember { mutableStateOf(VideoQuality.P1080) }

    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val engine = remember { DownloadEngine(context, binaryManager) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YTGrab", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Audio / Video toggle buttons above the search bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToggleChip(
                    label = "Audio",
                    icon = Icons.Filled.MusicNote,
                    selected = kind == MediaKind.AUDIO,
                    modifier = Modifier.weight(1f)
                ) { kind = MediaKind.AUDIO }

                ToggleChip(
                    label = "Video",
                    icon = Icons.Filled.Movie,
                    selected = kind == MediaKind.VIDEO,
                    modifier = Modifier.weight(1f)
                ) { kind = MediaKind.VIDEO }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Paste video link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Format/quality options depending on kind
            if (kind == MediaKind.AUDIO) {
                Text("Audio format", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AudioFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = audioFormat == fmt,
                            onClick = { audioFormat = fmt },
                            label = { Text(fmt.name) }
                        )
                    }
                }
            } else {
                Text("Video quality", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoQuality.entries.forEach { q ->
                        FilterChip(
                            selected = videoQuality == q,
                            onClick = { videoQuality = q },
                            label = { Text(q.label) }
                        )
                    }
                }
                Text("Output: MP4", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    if (url.isBlank()) return@Button
                    resultMessage = null
                    isDownloading = true
                    progress = 0f
                    scope.launch {
                        engine.download(url, kind, audioFormat, videoQuality).collect { event ->
                            when (event) {
                                is DownloadEvent.Progress -> {
                                    progress = event.percent / 100f
                                    progressLabel = "${event.percent.toInt()}% • ${event.speedText} • ETA ${event.etaText}"
                                }
                                is DownloadEvent.Done -> {
                                    isDownloading = false
                                    resultMessage = "Saved to: ${event.outputPath}"
                                }
                                is DownloadEvent.Failed -> {
                                    isDownloading = false
                                    resultMessage = "Failed: ${event.message}"
                                }
                                is DownloadEvent.LogLine -> { /* ignore in UI, shown in logcat */ }
                            }
                        }
                    }
                },
                enabled = binariesReady && !isDownloading && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isDownloading) "Downloading…" else "Download")
            }

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(progressLabel, style = MaterialTheme.typography.bodySmall)
            }

            resultMessage?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (binariesReady) "yt-dlp ${binaryManager.currentYtDlpVersion()}" else statusText,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    scope.launch {
                        statusText = "Checking…"
                        binaryManager.forceUpdateCheck { s -> statusText = s }
                    }
                }) {
                    Text("Check for updates")
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        }
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
