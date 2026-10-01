package dev.anonrode.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import dev.anonrode.player.core.media.log.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * User-initiated share of the app's own file log, focused on subtitle sync
 * (v0.7.2 device-fix round).
 *
 * Why this exists: both sync engines decide entirely at runtime — how many
 * bins accumulated, whether cues were attached when an evaluation slot
 * fired, which gate refused, whether the background job was ever scheduled
 * or dropped by a work constraint. "Sync never locks" on a real phone is
 * not answerable from source, and a bug report is only worth what the device
 * can prove. This reads the exact file [AppLog] writes
 * (`<filesDir>/logs/anonrode-player.log` plus its `.old` rotation), keeps
 * the newest sync-decision lines and the newest raw tail, prefixes device
 * and version facts, and hands the result to the system share sheet.
 *
 * Privacy: log lines contain video paths and subtitle file names, so the log
 * never leaves app-private storage on its own and this is never automatic —
 * only the "Sync log" tile in the player's overflow sheet triggers it, and
 * the user still has to pick a recipient in the share sheet.
 */
object SyncLogShare {

    private const val DIR = "logs"
    private const val FILE = "anonrode-player.log"

    /** Newest lines of the whole log kept as raw context. */
    private const val TAIL_LINES = 400

    /** Newest sync-decision lines kept regardless of where they sit. */
    private const val SYNC_LINES = 400

    /** Tags that carry the sync story, in order of how much we want them.
     *  Matching is `[$TAG]` exact — SUB_RESOLVER and SUB_TREE were MISSING
     *  until v0.8.1: their lines log under those tags and "[SUB]" never
     *  matched "[SUB_RESOLVER]", so every sidecar-discovery and SAF-tree
     *  verdict line was silently excluded from the report that exists to
     *  explain why a sync did or didn't happen. AB joined too: a quiet
     *  re-anchor decision is prime suspect material for "it unlocked wrong
     *  while I was looping". */
    private val SYNC_TAGS = listOf(
        "SYNC", "SYNC_JOB", "SYNC_ORCH", "SPOT_SYNC", "SYNC_FIND", "PIECEWISE",
        "INTERVAL", "ONSET", "VAD", "SUB", "SUB_RESOLVER", "SUB_TREE",
        "AB", "PLAY", "ENGINE",
    )

    /** Share-sheet text cap (clipper / messenger safety, not a log cap). */
    private const val MAX_SHARE_CHARS = 90_000

    /**
     * Build the report text, or null when the log cannot be read at all.
     * Blocking file IO (the log is capped at ~1.5 MB by AppLog's rotation,
     * so the read is bounded); [shareSyncLog] hops to IO for this.
     */
    fun buildReport(context: Context, extraDiagnostics: String? = null): String? = try {
        val dir = File(context.filesDir, DIR)
        val sources = listOf(File(dir, "$FILE.old"), File(dir, FILE))
            .filter { it.isFile }
        val lines = ArrayList<String>(2048)
        for (f in sources) {
            f.useLines { seq -> seq.forEach { lines.add(it) } }
        }
        if (lines.isEmpty()) return null
        val total = lines.size
        val sync = lines.filter { line -> SYNC_TAGS.any { line.contains("[$it]") } }
            .takeLast(SYNC_LINES)
        val tail = lines.takeLast(TAIL_LINES)
        val version = try {
            // versionName only: longVersionCode is API 28+ and minSdk is 23.
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }

        val app = context.applicationContext as? AnonrodeApp
        val engine = if (app?.isReady == true) app.engine else null

        val sb = StringBuilder(64 * 1024)
        sb.append("anonrode-player — subtitle sync log\n")
        sb.append("app version: ").append(version).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" · Android API ").append(Build.VERSION.SDK_INT)
            .append('\n')
        sb.append("log lines: ").append(total)
            .append(if (sources.size > 1) " (rotation file included)" else "").append('\n')

        if (engine != null) {
            val posMs = engine.player?.currentPosition ?: 0L
            val spd = engine.player?.playbackParameters?.speed ?: 1.0f
            sb.append("\n── active playback & sync state ──\n")
            sb.append("media uri: ").append(engine.currentUri ?: "none").append('\n')
            sb.append("position: ").append("%.2fs".format(posMs / 1000.0))
                .append(" (playback speed: ").append("%.2fx".format(spd)).append(")\n")
            sb.append("cues loaded: ").append(engine.activeSyncCues.size).append('\n')
            sb.append("live sync locked: ").append(engine.isLiveLocked)
                .append(" (offset: ").append("%.2fs".format(engine.subtitleOffsetMs / 1000.0))
                .append(", drift: ").append("%.4f".format(engine.subtitleSpeedFactor)).append(")\n")
            sb.append("persisted lock: ").append(engine.persistedAutoMs).append("ms\n")
            if (!extraDiagnostics.isNullOrBlank()) {
                sb.append(extraDiagnostics.trim()).append('\n')
            }
        } else if (!extraDiagnostics.isNullOrBlank()) {
            sb.append("\n── diagnostic notes ──\n").append(extraDiagnostics.trim()).append('\n')
        }

        // Quick milestone breakdown
        val lastLock = sync.findLast { it.contains("LOCKED offset=") || it.contains("spot probe locked:") }
        val cuts = sync.filter { it.contains("offset jump:") || it.contains("[PIECEWISE]") }
        if (lastLock != null || cuts.isNotEmpty()) {
            sb.append("\n── key sync milestones ──\n")
            lastLock?.let { sb.append("• latest lock: ").append(it.trim()).append('\n') }
            cuts.takeLast(3).forEach { sb.append("• cut event: ").append(it.trim()).append('\n') }
        }

        sb.append('\n')
        sb.append("── sync-decision lines (newest ").append(sync.size)
            .append(" of this session) ──\n")
        sync.forEach { sb.append(it).append('\n') }
        sb.append('\n').append("── raw log tail (newest ").append(tail.size)
            .append(" lines) ──\n")
        tail.forEach { sb.append(it).append('\n') }
        val text = sb.toString()
        if (text.length > MAX_SHARE_CHARS) {
            text.take(MAX_SHARE_CHARS) + "\n…(truncated)"
        } else {
            text
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Flush the logger, wait for its single writer thread to hit the disk,
     * read the log off the main thread, then open the share sheet with the
     * report. Call on the main thread (it hops to IO for the read and back
     * for the share). Never throws: the worst case is a toast and no sheet.
     */
    suspend fun shareSyncLog(context: Context, extraDiagnostics: String? = null) {
        AppLog.d("APP", "sync log share requested")
        AppLog.flush()
        // AppLog batches writes on its own daemon worker with a 1 s
        // coalescing window; flush() queues the drain, so give the worker a
        // moment before the read or the newest decisions are missing.
        delay(400)
        val text = withContext(Dispatchers.IO) { buildReport(context, extraDiagnostics) }
        if (text == null) {
            Toast.makeText(context, "No log file to share yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "anonrode-player sync log")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(send, "Share sync log")
            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            AppLog.e("APP", "sync log share failed", t)
            Toast.makeText(context, "Could not open the share sheet", Toast.LENGTH_SHORT).show()
        }
    }
}
