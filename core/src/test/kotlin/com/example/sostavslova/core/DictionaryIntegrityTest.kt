package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun containsModernWordsReportedMissingByPlayers() {
        for (w in listOf("оттиск", "насест", "тост", "кастет", "нить", "темнота", "табор")) {
            assertTrue(TestData.index.contains(w), "нет слова $w")
        }
    }

    @Test
    fun cleanupExcludesMarkedFormsAndKeepsIndependentMeanings() {
        for (word in listOf("ножик", "кровотеченье", "тать")) {
            assertFalse(TestData.index.contains(word), "Форма не должна входить в очищенную базу: $word")
        }
        for (word in listOf("механик", "ручка", "дерево", "каша", "зарплата", "профсоюз")) {
            assertTrue(TestData.index.contains(word), "Утрачено самостоятельное современное слово: $word")
        }
    }

    @Test
    fun cleanupPolicyAndCampaignExceptionsAreRecorded() {
        val selection = TestData.manifest.getJSONObject("selection")
        assertEquals("game-cleanup-v1", selection.getString("mode"))
        assertEquals(113413, selection.getInt("input_words"))
        assertFalse(selection.getBoolean("old_overrides_used"))
        assertFalse(selection.getBoolean("definitions_required"))
        assertTrue(selection.isNull("candidate_limit"))
        val exceptions = selection.getJSONObject("reference_checks").getJSONObject("level_exceptions")
        val levelWords = TestData.levels.map { it.word }.toSet()
        for (word in exceptions.keys()) {
            assertTrue(word in levelWords, "Исключение не относится к кампании: $word")
            assertTrue(TestData.index.contains(word), "Утрачено исключение кампании: $word")
            assertTrue(exceptions.getJSONArray(word).length() > 0)
        }
        assertEquals("frequency-list", selection.getJSONObject("frequency_source").getString("kind"))
    }

    @Test
    fun approvedCampaignReplacementsKeepNumericTargets() {
        assertEquals(Level(7, "велосипед", 10, 16, 'с', 6), TestData.levels[6])
        val weighing = TestData.levels[8]
        assertEquals(Level(9, "взвешивание", 7, 14, 'в', 5), weighing)
        val transfusion = TestData.levels[13]
        assertEquals(Level(14, "переливание", 10, 15, 'п', 6), transfusion)
        assertEquals(Level(23, "магнитофон", 12, 18, 'г', 5), TestData.levels[22])
        assertFalse(TestData.levels.any { it.word in setOf("намеривание", "перетекание", "передвигание", "симфонизация") })
    }

    @Test
    fun containsAllFrequentNounsFromGoldenList() {
        val resource = checkNotNull(javaClass.classLoader.getResourceAsStream("frequent_nouns_golden.txt"))
        val golden = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { it.isNotBlank() }
        val missing = golden.filterNot { TestData.index.contains(it) }
        assertTrue(missing.isEmpty(), "В словаре отсутствуют: ${missing.joinToString(", ")}")
    }

    @Test
    fun containsAllLevelWords() {
        val missing = TestData.levels.map { it.word }.filterNot { TestData.index.contains(it) }
        assertTrue(missing.isEmpty(), "В словаре отсутствуют слова уровней: ${missing.joinToString(", ")}")
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
