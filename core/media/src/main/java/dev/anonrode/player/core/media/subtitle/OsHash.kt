package dev.anonrode.player.core.media.subtitle

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles "moviehash" — the checksum the site uses for exact-file
 * matching: file size + sum of the first 64KB + sum of the last 64KB,
 * each chunk read as 8192 little-endian 64-bit integers, all arithmetic
 * mod 2^64. Hash-first search is what makes MX-style "find subtitles for
 * this exact file" work: an exact-hash hit means the subtitle was timed
 * for this very release.
 *
 * Kotlin's Long overflow wraps two's-complement, which is exactly mod-2^64,
 * and "%016x" renders negative values as their unsigned hex — matching the
 * reference C implementation byte for byte.
 */
object OsHash {

    data class Result(val hash: String, val sizeBytes: Long)

    private const val CHUNK = 64 * 1024

    /** Returns null if the file can't be read or is too small to hash. */
    fun compute(path: String): Result? = try {
        RandomAccessFile(path, "r").use { raf ->
            val size = raf.length()
            // The algorithm needs two distinct 64KB windows; below 128KB
            // the hash is degenerate and OpenSubtitles has no such file.
            if (size < 2L * CHUNK) return null

            var sum = size // hash seed = file size
            raf.seek(0)
            sum += sumChunk(raf)
            raf.seek(size - CHUNK)
            sum += sumChunk(raf)
            Result("%016x".format(sum), size)
        }
    } catch (t: Throwable) {
        null
    }

    private fun sumChunk(raf: RandomAccessFile): Long {
        val buf = ByteArray(CHUNK)
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
