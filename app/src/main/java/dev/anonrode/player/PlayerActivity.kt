package dev.anonrode.player

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.anonrode.player.core.media.subtitle.SubtitleParser
import dev.anonrode.player.core.model.SubtitleCue
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Minimal player screen — functional placeholder for the UI redesign.
 * Plays with Media3 + nextlib, restores resume position, applies the
 * persisted auto-sync offset + manual delay (additive), and renders the
 * active cue with the offset applied via binary search (ported from the
 * web player).
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        private val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var cueText by mutableStateOf<String?>(null)
    private var positionSec by mutableFloatStateOf(0f)
    private var durationSec by mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: uriStr
        val app = AnonrodeApp.get(this)
        val engine = app.engine

        // Resolve sidecar subtitles + start playback off the main thread.
        io.execute {
            val state = app.stateStore.get(uriStr)
            val parsed = findSidecarSubtitle(Uri.parse(uriStr))
                ?.let { (name, text) -> SubtitleParser.parse(name, text) }
                ?: emptyList()
            val manual = state?.subtitleDelayMs ?: 0L
            val auto = state?.autoSyncOffsetMs ?: 0L
            runOnUiThread {
                engine.play(MediaItem.fromUri(uriStr), uriStr, parsed, manual, auto)
                startRenderLoop(parsed)
            }
        }

        setContent {
            AnonrodeTheme {
                PlayerSurface(
                    engine = engine,
                    title = title,
                    cueText = cueText,
                    positionSec = positionSec,
                    durationSec = durationSec,
                    onSeek = { frac ->
                        engine.player.seekTo((frac * engine.player.duration.coerceAtLeast(1)).toLong())
                    },
                    onTogglePlay = {
                        if (engine.player.isPlaying) engine.player.pause() else engine.player.play()
                    },
                )
            }
        }
    }

    private fun startRenderLoop(cues: List<SubtitleCue>) {
        val tick = object : Runnable {
            override fun run() {
                val p = AnonrodeApp.get(this@PlayerActivity).engine.player
                positionSec = p.currentPosition / 1000f
                durationSec = (p.duration.takeIf { it > 0 } ?: 0L) / 1000f
                val t = p.currentPosition / 1000.0 - AnonrodeApp.get(this@PlayerActivity)
                    .engine.subtitleOffsetMs / 1000.0
                cueText = findCue(cues, t)?.lines?.joinToString("\n")
                handler.postDelayed(this, 100)
            }
        }
        handler.post(tick)
    }

    /** Binary-search cue lookup with offset applied (ported from web player). */
    private fun findCue(cues: List<SubtitleCue>, t: Double): SubtitleCue? {
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                t < c.start -> hi = mid - 1
                t > c.end -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    /**
     * Sidecar subtitle discovery: MediaStore.Files query for files whose
     * name starts with the video's base name and has a supported extension.
     */
    private fun findSidecarSubtitle(videoUri: Uri): Pair<String, String>? = try {
        val videoPath = contentResolver.query(
            videoUri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null

        val base = videoPath.substringAfterLast('/').substringBeforeLast('.')
        val filesUri = android.provider.MediaStore.Files.getContentUri("external")
        contentResolver.query(
            filesUri,
            arrayOf(android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$base%"),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in SUB_EXTS) continue
                val fileUri = ContentUris.withAppendedId(filesUri, c.getLong(0))
                val text = contentResolver.openInputStream(fileUri)
                    ?.bufferedReader()?.use { it.readText() } ?: continue
                if (text.isNotEmpty()) return name to text
            }
            null
        }
    } catch (_: Exception) {
        null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (AnonrodeApp.get(this)::engine.isInitialized) {
            AnonrodeApp.get(this).engine.savePositionNow()
        }
        super.onDestroy()
    }
}

@Composable
fun PlayerSurface(
    engine: dev.anonrode.player.feature.player.PlaybackEngine,
    title: String,
    cueText: String?,
    positionSec: Float,
    durationSec: Float,
    onSeek: (Float) -> Unit,
    onTogglePlay: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = engine.player
                    useController = false
                }
            }
        )
        cueText?.let { txt ->
            Text(
                txt,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(Color(0xAA000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onTogglePlay) { Text(if (engine.player.isPlaying) "Pause" else "Play") }
                Slider(
                    value = positionSec.coerceIn(0f, durationSec.coerceAtLeast(1f)),
                    onValueChange = { onSeek(it / durationSec.coerceAtLeast(1f)) },
                    valueRange = 0f..durationSec.coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
                )
            }
        }
    }
}
