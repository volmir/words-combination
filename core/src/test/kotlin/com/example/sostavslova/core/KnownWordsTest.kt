package com.example.sostavslova.core

import java.io.File
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KnownWordsTest {
    private val vectors = JSONObject(File("../tools/known_words_vectors.json").readText())
    private fun bytes(): ByteArray = vectors.getString("binary_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun sharedPythonVectorsAndNormalization() {
        val filter = assertNotNull(KnownWords.read(bytes()))
        val cases = vectors.getJSONArray("queries")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            assertEquals(row.getBoolean("expected"), filter.mightContain(row.getString("raw")), row.toString())
        }
    }

    @Test
    fun rejectsTruncationExtraBytesAndEverySingleByteCorruption() {
        val bytes = bytes()
        for (size in 0 until bytes.size) assertNull(KnownWords.read(bytes.copyOf(size)))
        assertNull(KnownWords.read(bytes + byteArrayOf(0)))
        for (i in bytes.indices) {
            val corrupt = bytes.copyOf()
            corrupt[i] = (corrupt[i].toInt() xor 1).toByte()
            assertNull(KnownWords.read(corrupt), "byte $i")
        }
        assertNull(KnownWords.read(null))
    }

    @Test
    fun ownsValidatedBytes() {
        val bytes = bytes()
        val filter = assertNotNull(KnownWords.read(bytes))
        bytes.fill(0)
        assertEquals(true, filter.mightContain("ЁЖИК"))
    }

    @Test
    fun exactDictionaryWorksWithoutFilterAndStillControlsScoring() {
        assertNull(KnownWords.read(null))
        val game = GameState.start("мирок", WordIndex(listOf("мир", "рок")))
        assertEquals(AnswerResult.CORRECT, game.submit("МИР").result)
        // «ком» составимо и положительно в фильтре из одних единичных битов,
        // но отсутствует в точном игровом словаре.
        val data = bytes()
        data.fill(0xff.toByte(), 28, data.size - 32)
        java.security.MessageDigest.getInstance("SHA-256").digest(data.copyOf(data.size - 32))
            .copyInto(data, data.size - 32)
        assertEquals(true, assertNotNull(KnownWords.read(data)).mightContain("ком"))
        assertEquals(AnswerResult.NOT_FOUND, game.submit("ком").result)
    }

    @Test
    fun rejectsUnsupportedAndInvalidHeadersEvenWithValidChecksum() {
        for ((offset, value) in listOf(8 to 2, 12 to 0, 12 to -8, 16 to 0, 16 to 33, 20 to 0, 24 to 2)) {
            val bytes = bytes()
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(bytes.size - 32))
            digest.copyInto(bytes, bytes.size - 32)
            assertNull(KnownWords.read(bytes), "$offset=$value")
        }
    }
}
