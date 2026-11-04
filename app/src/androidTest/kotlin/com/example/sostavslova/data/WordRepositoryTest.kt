package com.example.sostavslova.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class WordRepositoryTest {
    private fun goldenCount(word: String): Int = InstrumentationRegistry.getInstrumentation().context.assets
        .open("golden_search.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject(word).getInt("count") }
    private fun readWords() = InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open("words.txt").bufferedReader().use { it.readText() }

    @Test
    fun randomWordsAreDistinctAndPlayable() = runTest(timeout = 2.minutes) {
        val repository = WordRepository(readWords = { readWords() }, random = Random(42))
        // Вызываем с Main: само построение индекса и пула должно переключиться в фон.
        val words = withContext(Dispatchers.Main) { repository.randomSourceWords() }
        assertEquals(4, words.size)
        assertEquals(4, words.toSet().size)
        for (word in words) {
            assertTrue(word.length in 11..12)
            assertTrue(checkNotNull(repository.search(word)).total >= 20)
        }
        assertEquals(4, repository.randomSourceWords().toSet().size)
        assertEquals(emptyList<String>(), repository.randomSourceWords(0))
    }

    @Test
    fun concurrentAndRepeatedLoadsReadFileOnceOffMain() = runTest {
        val reads = AtomicInteger()
        val repository = WordRepository(readWords = {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            reads.incrementAndGet()
            delay(20)
            "рот\nтор\nторт\nтот\n"
        })
        withContext(Dispatchers.Main) {
            List(12) { async { repository.ensureLoaded() } }.awaitAll()
            repository.ensureLoaded()
        }
        assertEquals(1, reads.get())
        assertEquals(LoadState.Ready(4), repository.state.value)
        assertEquals(3, checkNotNull(repository.search("торт")).total)
        assertEquals(1, reads.get())
    }

    @Test
    fun searchDelegatesNormalizationAndGoldenCountsToCore() = runTest {
        val repository = WordRepository(readWords = { readWords() })
        for (word in listOf("портал", "акробат", "демонстрация")) {
            assertEquals(goldenCount(word), checkNotNull(repository.search(word)).total)
        }
        assertEquals(repository.search("ежик")?.words, repository.search(" ЁЖИК ")?.words)
        assertNull(repository.search("до"))
        assertNull(repository.search("123"))
    }

    @Test
    fun startGameNormalizesSourceAndFiltersRestoredAnswersUsingCore() = runTest {
        val repository = WordRepository(readWords = { readWords() })
        val game = repository.startGame(" ПОРТАЛ ", listOf("пар", "пар", "несуществующее"))
        assertEquals("портал", game.word)
        assertEquals(listOf("пар"), game.found)
        assertEquals(goldenCount("портал"), game.total)
    }

    @Test
    fun loadingFailureIsExposedAsState() = runTest {
        val cause = IOException("Missing test dictionary")
        val repository = WordRepository(readWords = { throw cause })
        repository.ensureLoaded()
        assertSame(cause, (repository.state.value as LoadState.Error).cause)
    }
}
