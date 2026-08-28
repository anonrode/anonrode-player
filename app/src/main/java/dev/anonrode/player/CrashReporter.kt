package dev.anonrode.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startup-crash self-reporter. The uncaught-exception handler is installed
 * in [android.app.Application.attachBaseContext] — before ANY ContentProvider
 * runs (androidx.startup / WorkManager initialize there) — so even the
 * earliest possible crash still gets captured.
 *
 * Each crash writes two copies:
 *  1. `<filesDir>/crash/last_crash.txt` — private; drives the report dialog
 *     shown on the next launch (MainActivity gates on it before composing
 *     the normal UI, so the dialog renders even when startup is broken).
 *  2. `Download/AnonPlayer/crash-<stamp>.txt` — public via MediaStore, no
 *     permission needed on API 29+, readable with any file manager even if
 *     the app itself can no longer start an activity.
 *
 * The dialog helpers ([summarize], [copyReport], [shareReport],
 * [restartApp]) are all defensive: the crash UI must never itself crash,
 * whatever state the report or the device is in.
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private val PUBLIC_DIR = Environment.DIRECTORY_DOWNLOADS + "/AnonPlayer"

    /** Binder transactions are limited to ~1 MB; keep shared text well under. */
    private const val MAX_SHARE_CHARS = 200_000

    /** Matches a throwable head line such as `java.lang.Foo: message`. */
    private val EX_HEAD = Regex("^([\\w$.]*(?:Exception|Error|Throwable))(?::\\s?(.*))?$")

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread.name, throwable)
            } catch (_: Throwable) {
                // Reporting must never mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Write a report for a caught (not uncaught) startup failure. */
    fun writeReport(context: Context, threadName: String, throwable: Throwable) {
        val report = buildReport(context, threadName, throwable)
        privateFile(context).also { it.parentFile?.mkdirs() }.writeText(report)
        writePublic(context, report)
    }

    /** Last crash report, or null if the previous run exited cleanly. */
    fun readLastCrash(context: Context): String? =
        try {
            privateFile(context).takeIf { it.exists() }?.readText()
        } catch (_: Throwable) {
            null
        }

    fun clearLastCrash(context: Context) {
        try {
            privateFile(context).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * One-line summary for the dialog header: exception class + message,
     * parsed from the report's stack-trace head. Never throws; falls back
     * to a generic string when the report is missing or unparseable.
     */
    fun summarize(report: String?): String {
        if (report.isNullOrBlank()) return "Crash report unavailable"
        for (line in report.lineSequence()) {
            val match = EX_HEAD.matchEntire(line.trim()) ?: continue
            val cls = match.groupValues[1].substringAfterLast('.')
            val message = match.groupValues[2].trim()
            return if (message.isEmpty()) cls else "$cls: ${message.take(160)}"
        }
        return "Crash report captured"
    }

    /** Copy the full report to the clipboard. Returns false on failure. */
    fun copyReport(context: Context, report: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("anonrode-player crash report", report))
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Open a system share sheet with the report as plain text. Returns
     * false when no activity could handle the share.
     */
    fun shareReport(context: Context, report: String): Boolean = try {
        val text = if (report.length > MAX_SHARE_CHARS) {
            report.take(MAX_SHARE_CHARS) + "\n…(report truncated)"
        } else {
            report
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "anonrode-player crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share crash report")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Restart the app: clear the stored crash (so the fresh launch shows
     * the normal UI), relaunch via the launcher intent with a clean task
     * stack, and tear down the current process UI. Every step is guarded —
     * worst case the user is left where they were, never crashed.
     */
    fun restartApp(context: Context) {
        clearLastCrash(context)
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
            }
        } catch (_: Throwable) {
        }
        try {
            (context as? Activity)?.finishAffinity()
        } catch (_: Throwable) {
        }
    }

    private fun privateFile(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun buildReport(context: Context, threadName: String, throwable: Throwable): String {
        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            "" + pi.versionName + " (" + code + ")"
        } catch (_: Throwable) {
            "?"
        }
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("anonrode-player crash report")
        pw.println("package: " + context.packageName)
        pw.println("version: " + version)
        pw.println("time: " + Date())
        pw.println(
            "device: " + Build.MANUFACTURER + " " + Build.MODEL +
                ", Android " + Build.VERSION.RELEASE +
                " (SDK " + Build.VERSION.SDK_INT + ")"
        )
        pw.println("thread: " + threadName)
        pw.println()
        // printStackTrace walks the cause chain for us; guard the head line
        // so a pathological throwable (null message is fine, but a throwing
        // toString() is not unheard of) can't kill the report.
        try {
            throwable.printStackTrace(pw)
        } catch (_: Throwable) {
            pw.println("(failed to render stack trace: " + throwable.javaClass.name + ")")
        }
        pw.flush()
        return sw.toString()
    }

    private fun writePublic(context: Context, report: String) {
        // Below Q there is no permissionless public-write path; the private
        // copy (and the next-launch dialog) still work there.
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "crash-$stamp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_DIR)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
        } catch (_: Throwable) {
            // Public copy is best-effort; the private one already landed.
        }
    }
}
