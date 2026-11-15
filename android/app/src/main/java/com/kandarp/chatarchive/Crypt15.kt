package com.kandarp.chatarchive

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts WhatsApp end-to-end encrypted backups (.crypt15) with the 64-digit key.
 * Same format and logic as indexer/wa_archive/crypt15.py (verified against wa-crypt-tools).
 *
 * The body is AES-256-GCM. GCM decryption = AES-CTR starting at inc32(J0), where for the
 * 16-byte IV WhatsApp uses, J0 = GHASH_H(IV || 0^64 || bitlen(IV)). We implement that CTR
 * ourselves so a multi-hundred-MB database streams to disk instead of being held in memory
 * (javax GCM buffers everything until the tag check). Correctness is checked instead by the
 * zlib stream inflating to a file that starts with the SQLite header.
 */
object Crypt15 {
    class DecryptException(msg: String) : Exception(msg)

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    fun parseKey(text: String): ByteArray {
        val hex = text.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        if (hex.length != 64) throw DecryptException(
            "The key must have 64 characters (0-9, a-f). You entered ${hex.length}."
        )
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun deriveBackupKey(root: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), root)
        return hmac(prk, "backup encryption".toByteArray() + byteArrayOf(1))
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg) }

    // ---- header -----------------------------------------------------------------------------

    class Header(val bodyOffset: Int, val ivs: List<ByteArray>)

    fun readHeader(head: ByteArray): Header {
        if (head.size < 64) throw DecryptException("File is too small to be a WhatsApp backup.")
        val size = head[0].toInt() and 0xFF
        val off = if (head[1].toInt() == 1) 2 else 1 // feature-table flag
        if (off + size > head.size) throw DecryptException("Backup header is damaged.")
        val proto = head.copyOfRange(off, off + size)
        val ivs = bytesFields(proto, 0).filter { it.size == 16 }
        if (ivs.isEmpty()) throw DecryptException("No IV in backup header. Is this a .crypt15 file?")
        return Header(off + size, ivs)
    }

    private fun bytesFields(buf: ByteArray, depth: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                if (pos >= buf.size || shift > 63) throw IllegalStateException()
                val b = buf[pos++].toInt() and 0xFF
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
            }
        }
        try {
            while (pos < buf.size) {
                val tag = varint()
                if (tag shr 3 == 0L) break
                when ((tag and 7).toInt()) {
                    0 -> varint()
                    1 -> pos += 8
                    5 -> pos += 4
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || pos + len > buf.size) break
                        val v = buf.copyOfRange(pos, pos + len)
                        pos += len
                        out += v
                        if (depth < 4) out += bytesFields(v, depth + 1)
                    }
                    else -> break
                }
            }
        } catch (_: IllegalStateException) { }
        return out
    }

    // ---- GCM counter mode ----------------------------------------------------------------------

    internal fun gfMul(x: ByteArray, y: ByteArray): ByteArray {
        var zHi = 0L; var zLo = 0L
        var vHi = toLong(y, 0); var vLo = toLong(y, 8)
        for (i in 0 until 128) {
            if ((x[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) { zHi = zHi xor vHi; zLo = zLo xor vLo }
            val lsb = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (lsb == 1L) vHi = vHi xor (0xE1L shl 56)
        }
        return fromLongs(zHi, zLo)
    }

    private fun toLong(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (b[o + i].toLong() and 0xFF)
        return r
    }

    private fun fromLongs(hi: Long, lo: Long) = ByteArray(16) { i ->
        (if (i < 8) hi ushr (56 - 8 * i) else lo ushr (56 - 8 * (i - 8))).toByte()
    }

    internal fun j0(aes: Cipher, iv: ByteArray): ByteArray {
        if (iv.size == 12) return iv + byteArrayOf(0, 0, 0, 1)
        val h = aes.doFinal(ByteArray(16))
        var y = ByteArray(16)
        val padded = iv.copyOf(((iv.size + 15) / 16) * 16)
        val lenBlock = ByteArray(16).also { val bits = iv.size.toLong() * 8
            for (i in 0 until 8) it[15 - i] = (bits ushr (8 * i)).toByte() }
        for (blk in padded.toList().chunked(16).map { it.toByteArray() } + listOf(lenBlock)) {
            y = gfMul(ByteArray(16) { (y[it].toInt() xor blk[it].toInt()).toByte() }, h)
        }
        return y
    }

    internal fun inc32(c: ByteArray) {
        for (i in 15 downTo 12) { c[i] = (c[i] + 1).toByte(); if (c[i].toInt() != 0) return }
    }

    /** XORs the stream with the GCM keystream (CTR from inc32(J0), wrapping in the low 32 bits). */
    private class GcmCtrStream(src: InputStream, private val aes: Cipher, j0: ByteArray) : FilterInputStream(src) {
        private val counter = j0.copyOf().also { inc32(it) }
        private val ks = ByteArray(16 * 1024)
        private val ctrBuf = ByteArray(ks.size)
        private var ksPos = ks.size

        private fun refill() {
            for (b in 0 until ks.size / 16) { System.arraycopy(counter, 0, ctrBuf, b * 16, 16); inc32(counter) }
            aes.doFinal(ctrBuf, 0, ctrBuf.size, ks, 0)
            ksPos = 0
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            for (i in 0 until maxOf(n, 0)) {
                if (ksPos == ks.size) refill()
                b[off + i] = (b[off + i].toInt() xor ks[ksPos++].toInt()).toByte()
            }
            return n
        }
    }

    /** InputStream.readNBytes needs Android 13; this works everywhere. */
    internal fun readUpTo(s: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var got = 0
        while (got < n) { val r = s.read(buf, got, n - got); if (r < 0) break; got += r }
        return if (got == n) buf else buf.copyOf(got)
    }

    /**
     * @param open opens a fresh stream over the whole .crypt15 file (called once per IV candidate)
     */
    fun decryptToFile(open: () -> InputStream, keyText: String, out: File) {
        val key = deriveBackupKey(parseKey(keyText))
        val head = open().use { readUpTo(it, 1024) }
        val header = readHeader(head)
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        for (iv in header.ivs) {
            val ok = open().use { raw ->
                readUpTo(raw, header.bodyOffset) // skip header
                val plain = InflaterInputStream(GcmCtrStream(raw.buffered(1 shl 16), aes, j0(aes, iv)), java.util.zip.Inflater(), 1 shl 16)
                try {
                    val first = readUpTo(plain, SQLITE_MAGIC.size)
                    if (!first.contentEquals(SQLITE_MAGIC)) return@use false
                    out.outputStream().buffered(1 shl 16).use { o -> o.write(first); plain.copyTo(o, 1 shl 16) }
                    true
                } catch (e: java.util.zip.ZipException) {
                    false // wrong key/IV -> not a zlib stream
                }
            }
            if (ok) return
        }
        out.delete()
        throw DecryptException(
            "Could not decrypt. The 64-digit key is probably wrong, or this backup was made before " +
                "you switched to the 64-digit key. Make a new backup in WhatsApp and try again."
        )
    }
}
