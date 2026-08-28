package dev.anonrode.player.core.media.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File logger — writes to app-private storage:
 * <filesDir>/logs/anonrode-player.log. Log lines contain video paths and
 * subtitle names, so they must not land in public storage.
 *
 * Design:
 * - Thread-safe timestamps: each thread formats through its own
 *   SimpleDateFormat (the class is not thread-safe and d()/e() are called
 *   from many threads).
 * - Batched writes: lines queue in memory and ONE delayed flush task drains
 *   them, so a burst of log calls costs one file open, not one per line.
 *   Errors flush immediately (they often precede a crash), and [flush]
 *   forces a drain on demand.
 * - Single writer: all disk IO happens on one daemon worker.
 * - Never throws: every public entry point swallows its own failures —
 *   logging must never crash the app. A failed write is reported once to
 *   logcat, then silently dropped.
 * - Size-capped with rotation to .old; the in-memory queue is capped too so
 *   a permanently failing disk cannot grow it without bound.
 */
object AppLog {

    private const val DIR = "logs"
    private const val FILE = "anonrode-player.log"
    private const val MAX_BYTES = 1_500_000L // rotate past ~1.5MB

    /** Coalescing window: lines logged within this span share one write. */
    private const val FLUSH_DELAY_MS = 1_000L

    /** Bound on queued lines if writes fail or init never happened. */
    private const val MAX_PENDING_LINES = 5_000

    private val pending = ConcurrentLinkedQueue<String>()
    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "app-log").apply { isDaemon = true }
        }

    /** Coalescing latch: at most one delayed flush task in flight. */
    private val flushScheduled = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var failedOnce = false

    // SimpleDateFormat is not thread-safe — give each thread its own.
    private val ts = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            d("APP", "logger initialized")
        } catch (t: Throwable) {
            // Never throw out of the logger.
        }
    }

    fun d(tag: String, msg: String) {
        try {
            enqueue("${ts.get().format(Date())} [$tag] $msg")
            scheduleFlush()
        } catch (t: Throwable) {
            // Never throw out of the logger.
        }
    }

    fun e(tag: String, msg: String, err: Throwable? = null) {
        try {
            val stack = err?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            enqueue("${ts.get().format(Date())} [$tag] $msg$stack")
            err?.stackTrace?.take(8)?.forEach { f -> enqueue("    at $f") }
            // Errors often precede a crash — hit the disk now, don't batch.
            worker.execute { writePending() }
        } catch (t: Throwable) {
            // Never throw out of the logger.
        }
    }

    /** Force-write everything queued (call on background thread or trust worker). */
    fun flush() {
        try {
            worker.execute { writePending() }
        } catch (t: Throwable) {
            // Never throw out of the logger.
        }
    }

    private fun enqueue(line: String) {
        pending.add(line)
        // Drop oldest lines if the queue outruns the disk (or init never
        // landed) — memory stays bounded either way.
        while (pending.size > MAX_PENDING_LINES) pending.poll()
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            worker.schedule({ writePending() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Drain the queue into one append. Worker-thread only. */
    private fun writePending() {
        val ctx = appContext
        if (ctx == null) {
            // Not initialized yet: leave the lines queued for later, but
            // release the coalescing latch so future flushes can schedule.
            flushScheduled.set(false)
            return
        }
        if (pending.isEmpty()) {
            flushScheduled.set(false)
            return
        }
        val sb = StringBuilder()
        while (true) {
            val line = pending.poll() ?: break
            sb.append(line).append('\n')
        }
        try {
            writePrivate(ctx, sb.toString())
            failedOnce = false
        } catch (e: Exception) {
            if (!failedOnce) {
                failedOnce = true // report once; don't loop
                try {
                    android.util.Log.e("AppLog", "write failed", e)
                } catch (t: Throwable) {
                    // Never throw out of the logger.
                }
            }
        } finally {
            // Release the latch AFTER the drain: any line that arrived
            // mid-write failed its scheduleFlush CAS, so re-check the queue
            // and schedule another pass when something is left.
            flushScheduled.set(false)
            if (pending.isNotEmpty()) scheduleFlush()
        }
    }

    /** Append to <filesDir>/logs/<FILE>, rotating to .old past the cap. */
    private fun writePrivate(ctx: Context, text: String) {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.isDirectory) dir.mkdirs()
        val f = File(dir, FILE)
        if (f.length() > MAX_BYTES) {
            val old = File(dir, "$FILE.old")
            old.delete()
            f.renameTo(old)
        }
        f.appendText(text)
    }

}
