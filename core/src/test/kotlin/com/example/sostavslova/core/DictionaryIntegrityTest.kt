package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.zip.GZIPInputStream
import java.security.MessageDigest
import java.net.URI
import org.json.JSONObject

class DictionaryIntegrityTest {
    private val words = TestData.index.words

    @Test
    fun countMatchesManifest() {
        assertEquals(TestData.manifest.getInt("words"), words.size)
    }

    @Test
    fun sortedUniqueAndValid() {
        assertEquals(words.sorted(), words)
        assertEquals(words.size, words.toSet().size)
        for (w in words) {
            assertTrue(w.length in Alphabet.MIN_WORD_LENGTH..Alphabet.MAX_WORD_LENGTH, w)
            assertTrue(w.all { Alphabet.indexOf(it) >= 0 }, w)
        }
    }

    @Test
    fun containsWordsReportedMissingByPlayers() {
        for (w in listOf("оттиск", "насест", "тост", "кастет", "нить", "темнота", "табор", "тать")) {
            assertTrue(TestData.index.contains(w), "нет слова $w")
        }
    }

    @Test
    fun dictionaryHasOpenProvenanceAndMatchesPublishedChecksum() {
        assertEquals("dictionary-provenance-v1", TestData.manifest.getString("schema"))
        val source = TestData.manifest.getJSONObject("source")
        assertEquals("CC-BY-SA-4.0", source.getString("license"))
        assertEquals("https://kaikki.org/ruwiktionary/rawdata.html", source.getString("url"))
        val digest = MessageDigest.getInstance("SHA-256").digest(TestData.wordsText.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(TestData.manifest.getString("sha256_words_txt"), digest)
    }

    @Test
    fun eachWordHasArticleProvenanceWithoutImportedDefinitionText() {
        val resource = checkNotNull(javaClass.classLoader.getResourceAsStream("word_sources.jsonl.gz"))
        val iterator = words.iterator()
        GZIPInputStream(resource).bufferedReader().useLines { lines ->
            for (line in lines) {
                val row = JSONObject(line)
                assertEquals(setOf("word", "sources"), row.keySet())
                assertTrue(iterator.hasNext(), "Лишнее слово в файле происхождения")
                val word = iterator.next()
                assertEquals(word, row.getString("word"))
                val sources = row.getJSONArray("sources")
                assertTrue(sources.length() > 0)
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    assertEquals(setOf("title", "line", "source"), source.keySet())
                    assertEquals(word, Alphabet.normalize(source.getString("title")))
                    assertTrue(source.getInt("line") > 0)
                    val uri = URI(source.getString("source"))
                    assertEquals("https", uri.scheme)
                    assertEquals("ru.wiktionary.org", uri.host)
                    assertEquals("/wiki/" + source.getString("title"), uri.path)
                }
            }
        }
        assertTrue(!iterator.hasNext(), "Есть слова без происхождения")
    }

    @Test
    fun measureLoadingTime() {
        val t0 = System.currentTimeMillis()
        val index = WordIndex.fromText(TestData.wordsText)
        val elapsed = System.currentTimeMillis() - t0
        println("words.txt load time: $elapsed ms (${index.size} words)")
        assertTrue(elapsed < 1000, "Загрузка words.txt должна занимать менее 1000 мс, фактически: $elapsed мс")
    }
}
