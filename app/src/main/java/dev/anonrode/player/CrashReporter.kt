package dev.anonrode.player

import android.content.ContentValues
import android.content.Context
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
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val PUBLIC_DIR = Environment.DIRECTORY_DOWNLOADS + "/AnonPlayer"

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
        privateFile(context).takeIf { it.exists() }?.readText()

    fun clearLastCrash(context: Context) {
        privateFile(context).delete()
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
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    private fun writePublic(context: Context, report: String) {
        // Below Q there is no permissionless public-write path; the private
        // copy (and the next-launch dialog) still work there.
        if (Build.VERSION.SDK_INT < 29) return
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
    }
}
