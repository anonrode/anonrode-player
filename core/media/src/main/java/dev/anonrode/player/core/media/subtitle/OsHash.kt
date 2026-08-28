package dev.anonrode.player.core.media.subtitle

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles "moviehash" — the checksum the site uses for exact-file
 * matching, per the official reference implementation:
 *
 *   hash = file_size
 *        + Σ first 64KB read as 8192 little-endian uint64
 *        + Σ last  64KB read as 8192 little-endian uint64
 *   (all arithmetic mod 2^64, rendered as 16 lowercase hex digits)
 *
 * Verified against the spec:
 *   - seed is the byte size, added before the chunk sums
 *   - chunks are parsed as LITTLE-endian 64-bit unsigned integers
 *   - the last window starts at (size − 64KB); for files between 64KB
 *     and 128KB the two windows overlap — the reference still sums both,
 *     so we only refuse files smaller than one 64KB window
 *   - Kotlin's Long overflow wraps two's-complement, which is exactly
 *     mod-2^64, and "%016x" renders negative values as their unsigned
 *     hex — matching the reference C implementation byte for byte.
 *
 * Hash-first search is what makes MX-style "find subtitles for this
 * exact file" work: an exact-hash hit means the subtitle was timed for
 * this very release.
 */
object OsHash {

    data class Result(val hash: String, val sizeBytes: Long)

    private const val CHUNK = 64 * 1024

    /** Returns null if the file can't be read or is too small to hash. */
    fun compute(path: String): Result? = try {
        val file = File(path)
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            // The algorithm needs at least one full 64KB window; the
            // reference seeks to (size - 64KB), which is only valid from
            // 64KB up. (Real videos are far larger; OpenSubtitles has no
            // subtitles for smaller files anyway.)
            if (size < CHUNK) return null

            val buf = ByteArray(CHUNK)
            var sum = size // hash seed = file size
            raf.seek(0)
            sum += sumChunk(raf, buf)
            raf.seek(size - CHUNK)
            sum += sumChunk(raf, buf)
            Result("%016x".format(sum), size)
        }
    } catch (t: Throwable) {
        null
    }

    /** Reads up to one CHUNK into [buf] and sums its LE u64 words. */
    private fun sumChunk(raf: RandomAccessFile, buf: ByteArray): Long {
        var off = 0
        while (off < CHUNK) {
            val n = raf.read(buf, off, CHUNK - off)
            if (n < 0) break
            off += n
        }
        val bb = ByteBuffer.wrap(buf, 0, off).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        while (bb.remaining() >= 8) sum += bb.long
        return sum
    }
}
