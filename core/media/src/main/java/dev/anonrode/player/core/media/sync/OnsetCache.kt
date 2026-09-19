package dev.anonrode.player.core.media.sync

import android.content.Context
import dev.anonrode.player.core.media.log.AppLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * v0.8.3 — on-disk onset extraction cache for the fingerprint job.
 *
 * Why this is an ACCURACY fix, not a speedup (09-15 device log): the
 * whole-file decode is budget-limited, and on this class of phone one
 * attempt only listened to 24–44% of a 45-minute episode. Every retry
 * started the decode from zero again, and a WorkManager cancellation
 * (process death) threw a finished extraction away entirely — so the
 * engine repeatedly locked fits extrapolated from a fraction of the
 * file. Slope errors of exactly that shape measured up to 1.5 s of beta
 * drift in the 70%-coverage simulation, before even reaching the real
 * coverage. The cache turns "one truncated pass" into "N resumable
 * passes over the same decode clock": attempt k resumes at the media
 * time attempt k−1 reached, and the verdict is finally fitted over
 * 100% of the audio.
 *
 * Keyed by the VIDEO only (SHA-1 of the content URI, validated against
 * file size+mtime): onsets are a property of the audio track, so a
 * subtitle edit must never invalidate them. [.ver] bumps when the
 * onset detectors themselves change semantics.
 */
object OnsetCache {

    private const val MAGIC = 0x4F430001L
    /** Bump when the detectors' onset/envelope semantics change (invalidates all). */
    private const val VERSION = 2L

    data class Entry(
        val silencedetect: List<Double>,
        val vad: List<Double>,
        val envelope: FloatArray = FloatArray(0),
        /** Absolute media time (s) covered by the stored onset lists. */
        val coveredSec: Double,
        /** True when the stored lists cover the whole file (no resume needed). */
        val complete: Boolean,
    ) {
        fun asSources() = OnsetExtractor.OnsetSources(silencedetect, vad, envelope)
    }

    private fun fileFor(context: Context, videoUri: String): File {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest(videoUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "onset-cache")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "$sha.bin")
    }

    /** Null unless the file exists, carries the current version and the
     *  video file still has the same size+mtime it was extracted from. */
    fun load(context: Context, videoUri: String, videoFile: File): Entry? {
        val f = fileFor(context, videoUri)
        if (!f.isFile) return null
        return try {
            DataInputStream(f.inputStream()).use { d ->
                if (d.readLong() != MAGIC) return null
                if (d.readLong() != VERSION) return null
                if (d.readLong() != videoFile.length()) return null
                if (d.readLong() != videoFile.lastModified()) return null
                val covered = d.readDouble()
                val complete = d.readBoolean()
                val sil = readDoubles(d)
                val vad = readDoubles(d)
                val env = readFloats(d)
                Entry(sil, vad, env, covered, complete)
            }
        } catch (t: Throwable) {
            AppLog.d("SYNC_JOB", "onset cache read failed, extracting fresh")
            runCatching { f.delete() }
            null
        }
    }

    /** Write via temp+rename so a process death mid-write can never expose
     *  a half-written entry (the rename is atomic on ext4/apfs). */
    fun store(
        context: Context,
        videoUri: String,
        videoFile: File,
        entry: Entry,
    ) {
        val f = fileFor(context, videoUri)
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            DataOutputStream(tmp.outputStream()).use { d ->
                d.writeLong(MAGIC)
                d.writeLong(VERSION)
                d.writeLong(videoFile.length())
                d.writeLong(videoFile.lastModified())
                d.writeDouble(entry.coveredSec)
                d.writeBoolean(entry.complete)
                writeDoubles(d, entry.silencedetect)
                writeDoubles(d, entry.vad)
                writeFloats(d, entry.envelope)
            }
            if (!tmp.renameTo(f)) {
                runCatching { f.delete() }
                if (!tmp.renameTo(f)) return
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
        }
    }

    fun clear(context: Context, videoUri: String) {
        runCatching { fileFor(context, videoUri).delete() }
    }

    /** Union of a cached prefix and a fresh resumed suffix: sorted, and
     *  de-duplicated inside the 50 ms seam the detectors cannot resolve
     *  anyway (hybrid's own dedup constant). */
    fun mergeOnsets(prefix: List<Double>, suffix: List<Double>): List<Double> {
        if (prefix.isEmpty()) return suffix
        if (suffix.isEmpty()) return prefix
        val out = ArrayList<Double>(prefix.size + suffix.size)
        for (t in (prefix + suffix).sorted()) {
            if (out.isEmpty() || t - out.last() > 0.05) out.add(t)
        }
        return out
    }

    /** Stitch together continuous envelopes from a cached prefix and resumed suffix. */
    fun mergeEnvelope(
        prefix: FloatArray,
        suffix: FloatArray,
        prefixCoveredSec: Double,
        binSec: Double = 0.1,
    ): FloatArray {
        if (prefix.isEmpty()) return suffix
        if (suffix.isEmpty()) return prefix
        val prefixBins = maxOf(0, (prefixCoveredSec / binSec).toInt()).coerceAtMost(prefix.size)
        val totalBins = prefixBins + suffix.size
        val out = FloatArray(totalBins)
        System.arraycopy(prefix, 0, out, 0, prefixBins)
        System.arraycopy(suffix, 0, out, prefixBins, suffix.size)
        return out
    }

    private fun readDoubles(d: DataInputStream): List<Double> {
        val n = d.readInt().coerceAtMost(2_000_000)
        if (n <= 0) return emptyList()
        return DoubleArray(n) { d.readDouble() }.asList()
    }

    private fun writeDoubles(d: DataOutputStream, v: List<Double>) {
        d.writeInt(v.size)
        for (x in v) d.writeDouble(x)
    }

    private fun readFloats(d: DataInputStream): FloatArray {
        val n = d.readInt().coerceAtMost(2_000_000)
        if (n <= 0) return FloatArray(0)
        val arr = FloatArray(n)
        for (i in 0 until n) arr[i] = d.readFloat()
        return arr
    }

    private fun writeFloats(d: DataOutputStream, arr: FloatArray) {
        d.writeInt(arr.size)
        for (x in arr) d.writeFloat(x)
    }
}
