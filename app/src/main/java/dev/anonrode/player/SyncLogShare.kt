package dev.anonrode.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import dev.anonrode.player.core.media.log.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-initiated share of the app's own file log, focused on subtitle sync.
 *
 * Reads <filesDir>/logs/anonrode-player.log (plus .old rotation) on Dispatchers.IO,
 * formats active playback and sync state captured safely on the main thread,
 * and hands the formatted diagnostic report to the system share sheet.
 */
object SyncLogShare {

    private const val DIR = "logs"
    private const val FILE = "anonrode-player.log"

    /** Newest lines of the whole log kept as raw context. */
    private const val TAIL_LINES = 400

    /** Newest sync-decision lines kept regardless of where they sit. */
    private const val SYNC_LINES = 400

    /** Tags that carry the sync story, in order of how much we want them. */
    private val SYNC_TAGS = listOf(
        "SYNC", "SYNC_JOB", "SYNC_ORCH", "SPOT_SYNC", "SYNC_FIND", "PIECEWISE",
        "INTERVAL", "ONSET", "VAD", "SUB", "SUB_RESOLVER", "SUB_TREE",
        "AB", "PLAY", "ENGINE",
    )

    /** Share-sheet text cap (clipper / messenger safety, not a log cap). */
    private const val MAX_SHARE_CHARS = 90_000

    /**
     * In-memory snapshot of playback and subtitle synchronization state.
     * Captured strictly on [Dispatchers.Main] to prevent Media3 / ExoPlayer
     * IllegalStateException ("Player is accessed on the wrong thread").
     */
    data class SyncSnapshot(
        val uri: String? = null,
        val positionMs: Long = 0L,
        val speed: Float = 1.0f,
        val cuesLoaded: Int = 0,
        val isLiveLocked: Boolean = false,
        val offsetMs: Long = 0L,
        val drift: Float = 1.0f,
        val persistedLockMs: Long = 0L,
        val hasEngine: Boolean = false,
    )

    /**
     * Safely capture playback and sync metrics on the Main looper.
     */
    suspend fun captureSnapshot(context: Context): SyncSnapshot = withContext(Dispatchers.Main.immediate) {
        try {
            val app = context.applicationContext as? AnonrodeApp
            val engine = if (app?.isReady == true) app.engine else null
            val activityUri = (context as? PlayerActivity)?.activeUriStr
            if (engine != null) {
                val player = engine.player
                val posMs = try { player?.currentPosition ?: 0L } catch (_: Throwable) { 0L }
                val spd = try { player?.playbackParameters?.speed ?: 1.0f } catch (_: Throwable) { 1.0f }
                val activeUri = activityUri ?: engine.currentUri
                SyncSnapshot(
                    uri = activeUri,
                    positionMs = posMs,
                    speed = spd,
                    cuesLoaded = engine.activeSyncCues.size,
                    isLiveLocked = engine.isLiveLocked,
                    offsetMs = engine.subtitleOffsetMs,
                    drift = engine.subtitleSpeedFactor,
                    persistedLockMs = engine.persistedAutoMs,
                    hasEngine = true,
                )
            } else {
                SyncSnapshot(
                    uri = activityUri,
                    hasEngine = false,
                )
            }
        } catch (_: Throwable) {
            SyncSnapshot(hasEngine = false)
        }
    }

    /**
     * Build the full diagnostic report.
     * Reads log files from disk on Dispatchers.IO and formats active playback metrics.
     * Never accesses [ExoPlayer] directly, avoiding thread-check exceptions.
     */
    fun buildReport(
        context: Context,
        snapshot: SyncSnapshot? = null,
        extraDiagnostics: String? = null,
    ): String = try {
        val dir = File(context.filesDir, DIR)
        val sources = listOf(File(dir, "$FILE.old"), File(dir, FILE))
            .filter { it.isFile }
        val lines = ArrayList<String>(2048)
        for (f in sources) {
            try {
                f.useLines { seq -> seq.forEach { lines.add(it) } }
            } catch (t: Throwable) {
                AppLog.e("APP", "failed reading log file: ${f.name}", t)
            }
        }
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

        val sb = StringBuilder(64 * 1024)
        sb.append("anonrode-player — subtitle sync log\n")
        sb.append("app version: ").append(version).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" · Android API ").append(Build.VERSION.SDK_INT)
            .append('\n')
        sb.append("log lines on disk: ").append(total)
            .append(if (sources.size > 1) " (rotation file included)" else "").append('\n')

        if (snapshot != null && snapshot.hasEngine) {
            sb.append("\n── active playback & sync state ──\n")
            sb.append("media uri: ").append(snapshot.uri ?: "none (idle/library)").append('\n')
            sb.append("position: ").append("%.2fs".format(snapshot.positionMs / 1000.0))
                .append(" (playback speed: ").append("%.2fx".format(snapshot.speed)).append(")\n")
            sb.append("cues loaded: ").append(snapshot.cuesLoaded).append('\n')
            sb.append("live sync locked: ").append(snapshot.isLiveLocked)
                .append(" (offset: ").append("%.2fs".format(snapshot.offsetMs / 1000.0))
                .append(", drift: ").append("%.4f".format(snapshot.drift)).append(")\n")
            sb.append("persisted lock: ").append(snapshot.persistedLockMs).append("ms\n")
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

        if (total == 0) {
            sb.append("\n── log status ──\n")
            sb.append("no log lines recorded to disk yet (filesDir: ${dir.absolutePath})\n")
        } else {
            sb.append('\n')
            sb.append("── sync-decision lines (newest ").append(sync.size)
                .append(" of this session) ──\n")
            sync.forEach { sb.append(it).append('\n') }
            sb.append('\n').append("── raw log tail (newest ").append(tail.size)
                .append(" lines) ──\n")
            tail.forEach { sb.append(it).append('\n') }
        }

        val text = sb.toString()
        if (text.length > MAX_SHARE_CHARS) {
            text.take(MAX_SHARE_CHARS) + "\n…(truncated)"
        } else {
            text
        }
    } catch (t: Throwable) {
        AppLog.e("APP", "buildReport failed", t)
        "anonrode-player — subtitle sync log (partial snapshot)\n" +
            "report generation error: ${t.javaClass.simpleName} - ${t.message}\n" +
            "media uri: ${snapshot?.uri ?: "none"}\n" +
            "position: %.2fs\n".format((snapshot?.positionMs ?: 0L) / 1000.0) +
            "cues loaded: ${snapshot?.cuesLoaded ?: 0}\n" +
            "live locked: ${snapshot?.isLiveLocked ?: false}\n" +
            (extraDiagnostics?.let { "\n$it\n" } ?: "")
    }

    /**
     * Backward-compatible 2-argument overload for callers not passing a pre-captured snapshot.
     */
    fun buildReport(context: Context, extraDiagnostics: String? = null): String =
        buildReport(context, null, extraDiagnostics)

    /**
     * Flush the logger, read the log off the main thread, and open the system share sheet.
     * Safely captures player state on the main thread first, so ExoPlayer is never
     * touched from Dispatchers.IO.
     */
    suspend fun shareSyncLog(context: Context, extraDiagnostics: String? = null) {
        AppLog.init(context)
        AppLog.d("APP", "sync log share requested")
        val snapshot = captureSnapshot(context)
        withContext(Dispatchers.IO) {
            AppLog.flushSync(1_000L)
        }
        val text = withContext(Dispatchers.IO) {
            buildReport(context, snapshot, extraDiagnostics)
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
