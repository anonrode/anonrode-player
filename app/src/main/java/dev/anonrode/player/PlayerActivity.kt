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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.media.subtitle.SubtitleParser
import dev.anonrode.player.core.model.SubtitleCue
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.ui.PlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the PlayerScreen. Playback wiring: restore resume position, apply
 * persisted auto-sync offset + manual delay (additive), resolve sidecar
 * subtitles, and drive the subtitle render loop (binary search + offset).
 * State fields are Compose-backed so only affected UI recomposes.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        private val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa")
    }

    private val handler = Handler(Looper.getMainLooper())

    private var cueText by mutableStateOf<String?>(null)
    private var positionSec by mutableStateOf(0f)
    private var durationSec by mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
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

        setContent {
            AnonrodeTheme {
                PlayerScreen(
                    player = engine.player,
                    title = title,
                    cueText = cueText,
                    positionSec = positionSec,
                    durationSec = durationSec,
                    onBack = { finish() },
                )
            }
        }

        // Resolve sidecar subtitles + start playback off the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val state = app.stateStore.get(uriStr)
            val parsed = findSidecarSubtitle(Uri.parse(uriStr))
                ?.let { (name, text) -> SubtitleParser.parse(name, text) }
                ?: emptyList()
            val manual = state?.subtitleDelayMs ?: 0L
            val auto = state?.autoSyncOffsetMs ?: 0L
            withContext(Dispatchers.Main) {
                engine.play(MediaItem.fromUri(uriStr), uriStr, parsed, manual, auto)
                startRenderLoop(parsed)
            }
        }
    }

    private fun startRenderLoop(cues: List<SubtitleCue>) {
        val tick = object : Runnable {
            override fun run() {
                val engine = AnonrodeApp.get(this@PlayerActivity).engine
                val p = engine.player
                positionSec = p.currentPosition / 1000f
                durationSec = (p.duration.takeIf { it > 0 } ?: 0L) / 1000f
                val t = p.currentPosition / 1000.0 - engine.subtitleOffsetMs / 1000.0
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
        AnonrodeApp.get(this).engine.savePositionNow()
        super.onDestroy()
    }
}
