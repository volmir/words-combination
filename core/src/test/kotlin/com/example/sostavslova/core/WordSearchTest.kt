package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordSearchTest {
    private val search = WordSearch(TestData.index)

    @Test
    fun matchesGoldenResultsFromReferenceImplementation() {
        val golden = TestData.golden
        for (source in golden.keys()) {
            val expected = golden.getJSONObject(source).getJSONArray("words").let { a ->
                (0 until a.length()).map { a.getString(it) }
            }
            val actual = search.searchNormalized(source).words
            assertEquals(expected, actual, "Слово «$source»")
            assertEquals(golden.getJSONObject(source).getInt("count"), actual.size)
        }
    }

    @Test
    fun respectsLetterMultiplicity() {
        val index = WordIndex(listOf("рот", "тор", "торт", "тот"))
        // из «рот» нельзя составить «тот» (нужны две «т») и «торт»
        assertEquals(listOf("тор"), index.findBuildable("рот"))
        // из «торт» - можно «тот», «рот», «тор»
        assertEquals(setOf("рот", "тор", "тот"), index.findBuildable("торт").toSet())
    }

    @Test
    fun excludesSourceWordItself() {
        assertFalse("портал" in search.searchNormalized("портал").words)
    }

    @Test
    fun invalidInputReturnsNull() {
        assertEquals(null, search.search("до"))
        assertEquals(null, search.search("123"))
    }

    @Test
    fun inputIsCaseInsensitive() {
        val a = search.search("ПОРТАЛ")!!.words
        val b = search.search("портал")!!.words
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun resultsAreGroupedByLength() {
        val r = search.searchNormalized("демонстрация")
        assertEquals(r.total, r.byLength.values.sumOf { it.size })
        assertEquals(r.byLength.keys.sorted(), r.byLength.keys.toList())
    }

    @Test
    fun searchIsFast() {
        search.searchNormalized("питекантроп") // прогрев
        val t0 = System.nanoTime()
        repeat(20) { search.searchNormalized("каменистость") }
        val avgMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue(avgMs < 100, "Слишком медленно: $avgMs мс")
    }

    @Test
    fun acceptanceCriteriaStage7() {
        val r = search.searchNormalized("демонстрация")
        assertEquals(883, r.total)
        for ((length, words) in r.byLength) {
            assertEquals(words.sorted(), words, "Слова длины $length должны быть отсортированы по алфавиту")
        }
        val yoSearch = search.search("ёж")
        val eSearch = search.search("еж")
        assertEquals(yoSearch, eSearch)
        assertEquals(null, yoSearch)

        val yoHedgehog = search.search("ёжик")
        val eHedgehog = search.search("ежик")
        assertEquals(yoHedgehog?.words, eHedgehog?.words)
    }
}
