package com.example.sostavslova.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Вероятностный справочник известных слов. Только игровой словарь разрешает зачёт ответа. */
class KnownWords private constructor(private val bits: ByteArray, private val bitCount: Int, private val hashes: Int) {
    fun mightContain(raw: String): Boolean {
        val word = Alphabet.normalize(raw)
        if (word.isEmpty()) return false
        val digest = ByteBuffer.wrap(sha256(word.toByteArray(Charsets.UTF_8))).order(ByteOrder.LITTLE_ENDIAN)
        repeat(hashes) {
            val index = ((digest.int.toLong() and 0xffffffffL) % bitCount).toInt()
            if ((bits[index / 8].toInt() and (1 shl (index % 8))) == 0) return false
        }
        return true
    }

    companion object {
        private const val HEADER = 28
        private const val CHECKSUM = 32
        private const val MAX_BITS = 128 * 1024 * 1024
        private val MAGIC = "RUKNOWN\u0000".toByteArray(Charsets.US_ASCII)
        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        /** null означает отсутствие или повреждение фильтра; точный игровой словарь остаётся доступен. */
        fun read(bytes: ByteArray?): KnownWords? {
            if (bytes == null || bytes.size < HEADER + CHECKSUM || bytes.size > MAX_BITS / 8 + HEADER + CHECKSUM) return null
            val data = bytes.copyOf()
            if (!data.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            header.position(8)
            val version = header.int
            val bitCount = header.int
            val hashes = header.int
            val words = header.int
            val algorithm = header.int
            if (version != 1 || algorithm != 1 || bitCount !in 8..MAX_BITS || bitCount % 8 != 0 ||
                hashes !in 1..8 || words <= 0 || data.size != HEADER + bitCount / 8 + CHECKSUM) return null
            val content = data.copyOf(data.size - CHECKSUM)
            if (!MessageDigest.isEqual(sha256(content), data.copyOfRange(content.size, data.size))) return null
            return KnownWords(data.copyOfRange(HEADER, content.size), bitCount, hashes)
        }
    }
}
