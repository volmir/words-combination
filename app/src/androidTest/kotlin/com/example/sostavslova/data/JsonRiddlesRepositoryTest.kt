package com.example.sostavslova.data

import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonRiddlesRepositoryTest {
    private fun asset() = InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open("owned_riddles.json").use { it.readBytes() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun allElevenExactTextsAreReadFromAssetOnceAndNormalizeYo() = runTest {
        val bytes = asset()
        val count = AtomicInteger()
        val repository = JsonRiddlesRepository({ count.incrementAndGet(); bytes })
        assertEquals(BuildConfig.RIDDLES_SHA256, sha(bytes))
        val expected = JSONObject(bytes.decodeToString()).getJSONObject("riddles")
        assertEquals(11, expected.length())
        for (word in expected.keys()) {
            assertEquals(expected.getString(word), repository.textFor(word))
            assertEquals(expected.getString(word), repository.textFor(word.uppercase()))
        }
        assertEquals(expected.getString("ковер"), repository.textFor("КОВЁР"))
        assertEquals(1, count.get())
    }

    @Test fun corruptionMissingKeysDuplicateFieldsAndWrongLicenseFailClosedEvenWithMatchingSha() = runTest {
        val raw = asset().decodeToString()
        val missing = JSONObject(raw).apply { getJSONObject("riddles").remove("банк") }.toString()
        val extra = JSONObject(raw).apply { getJSONObject("riddles").put("дом", "Чужой текст") }.toString()
        val empty = JSONObject(raw).apply { getJSONObject("riddles").put("банк", "") }.toString()
        val mutations = listOf("{broken", missing, extra, empty, raw.replace("CC0-1.0", "MIT"),
            raw.replace("\"version\":1", "\"version\":true"),
            raw.replace("\"банк\":", "\"банк\":\"duplicate\",\"банк\":"), raw + "\n")
        for (text in mutations) {
            val bytes = text.encodeToByteArray()
            val repository = JsonRiddlesRepository({ bytes }, sha(bytes))
            try { repository.textFor("банк"); fail("Accepted malformed content") }
            catch (_: IllegalArgumentException) { }
        }
        val changed = raw.replace("Финансовая", "Синтетическая").encodeToByteArray()
        try { JsonRiddlesRepository({ changed }).textFor("банк"); fail("Accepted wrong SHA") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun unknownTargetFailsWithoutCreatingFallbackOrFiles() = runTest {
        try { JsonRiddlesRepository(::asset).textFor("дом"); fail("Accepted unknown target") }
        catch (_: IllegalArgumentException) { }
    }
}
