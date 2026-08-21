package dev.anonrode.player.core.media.log

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * File logger — everything the app does lands in a normal, user-visible
 * folder: /storage/emulated/0/Download/AnonPlayer/anonrode-player.log
 *
 * Written through MediaStore.Downloads (no storage permission required on
 * API 29+; direct file fallback below 29). Append-mode, size-capped with
 * rotation to .old. Thread-safe: disk writes happen on a single worker.
 */
object AppLog {

    private const val DIR = "AnonPlayer"
    private const val FILE = "anonrode-player.log"
    private const val MAX_BYTES = 1_500_000L // rotate past ~1.5MB

    private val pending = ConcurrentLinkedQueue<String>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var appContext: Context? = null
    @Volatile private var failedOnce = false

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
        d("APP", "logger initialized")
    }

    fun d(tag: String, msg: String) {
        val line = "${ts.format(Date())} [$tag] $msg"
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
            if (Build.VERSION.SDK_INT >= 29) {
                writeViaMediaStore(sb.toString())
            } else {
                writeDirect(sb.toString())
            }
            failedOnce = false
        } catch (e: Exception) {
            if (!failedOnce) {
                failedOnce = true // log once; don't loop
                android.util.Log.e("AppLog", "write failed", e)
            }
        }
    }

    private fun resolveExisting(resolver: android.content.ContentResolver): Long? {
        val uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(
            uri,
            arrayOf(android.provider.MediaStore.Files.FileColumns._ID),
            "${android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
                "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
            arrayOf("Download/$DIR/", FILE),
            null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    private fun writeViaMediaStore(text: String) {
        val resolver = appContext!!.contentResolver
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, FILE)
            put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/$DIR")
        }
        val existingId = resolveExisting(resolver)
        val existing = existingId?.let { android.content.ContentUris.withAppendedId(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), it) }
        if (existing == null) {
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("insert returned null")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } else {
            val size = querySize(resolver, existing)
            if (size > MAX_BYTES) {
                // rotate: delete, next write recreates fresh
                resolver.delete(existing, null, null)
                val uri = resolver.insert(MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw IllegalStateException("insert returned null")
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            } else {
                resolver.openOutputStream(existing, "wa")?.use { it.write(text.toByteArray()) }
            }
        }
    }

    private fun querySize(resolver: android.content.ContentResolver, fileUri: Uri): Long {
        resolver.query(fileUri, arrayOf(android.provider.MediaStore.Files.FileColumns.SIZE),
            null, null, null)?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0
    }

    private fun writeDirect(text: String) {
        val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR)
        dir.mkdirs()
        val f = java.io.File(dir, FILE)
        if (f.length() > MAX_BYTES) {
            val old = java.io.File(dir, "$FILE.old")
            f.renameTo(old)
        }
        f.appendText(text)
    }

}
