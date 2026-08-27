package dev.anonrode.player.core.media.log

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * File logger — writes to app-private storage:
 * <filesDir>/logs/anonrode-player.log. Log lines contain video paths and
 * subtitle names, so they must not land in public storage. Append-mode,
 * size-capped with rotation to .old. Thread-safe: each thread formats its
 * timestamps through its own SimpleDateFormat, and disk writes happen on a
 * single worker.
 */
object AppLog {

    private const val DIR = "logs"
    private const val FILE = "anonrode-player.log"
    private const val MAX_BYTES = 1_500_000L // rotate past ~1.5MB

    private val pending = ConcurrentLinkedQueue<String>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var appContext: Context? = null
    @Volatile private var failedOnce = false

    // SimpleDateFormat is not thread-safe and d()/e() are called from many
    // threads — give each thread its own instance.
    private val ts = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        d("APP", "logger initialized")
    }

    fun d(tag: String, msg: String) {
        val line = "${ts.get().format(Date())} [$tag] $msg"
        pending.add(line)
        worker.execute { writePending() }
    }

    fun e(tag: String, msg: String, err: Throwable? = null) {
        val stack = err?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        d(tag, "$msg$stack")
        err?.stackTrace?.take(8)?.forEach { f -> pending.add("    at $f") }
        worker.execute { writePending() }
    }

    /** Force-write everything queued (call on background thread or trust worker). */
    fun flush() {
        worker.execute { writePending() }
    }

    private fun writePending() {
        val ctx = appContext ?: return
        if (pending.isEmpty()) return
        val sb = StringBuilder()
        var line = pending.poll()
        while (line != null) {
            sb.append(line).append('\n')
            line = pending.poll()
        }
        try {
            writePrivate(ctx, sb.toString())
            failedOnce = false
        } catch (e: Exception) {
            if (!failedOnce) {
                failedOnce = true // log once; don't loop
                android.util.Log.e("AppLog", "write failed", e)
            }
        }
    }

    /** Append to <filesDir>/logs/<FILE>, rotating to .old past the cap. */
    private fun writePrivate(ctx: Context, text: String) {
        val dir = java.io.File(ctx.filesDir, DIR)
        if (!dir.isDirectory) dir.mkdirs()
        val f = java.io.File(dir, FILE)
        if (f.length() > MAX_BYTES) {
            val old = java.io.File(dir, "$FILE.old")
            old.delete()
            f.renameTo(old)
        }
        f.appendText(text)
    }

}
