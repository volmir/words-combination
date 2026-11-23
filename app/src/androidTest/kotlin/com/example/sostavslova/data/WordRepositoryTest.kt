package com.example.sostavslova.data

import android.os.Looper
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CurrentCampaignLevel
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.WordFamiliarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
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
    fun familiarityIsLazyCachedAndOnlyMapsRequestedLevelWords() = runTest {
        val reads = AtomicInteger()
        val repository = WordRepository(
            readWords = { "кот\nкрот\nколея\nстанция\n" },
            readFamiliarity = {
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                reads.incrementAndGet()
                "0\n1\n2\n3\n"
            },
        )
        repository.ensureLoaded()
        assertEquals(0, reads.get())
        withContext(Dispatchers.Main) {
            val maps = List(12) { async { repository.hintWordData(listOf("крот", "станция", "неизвестное")) } }.awaitAll()
            for (data in maps) {
                assertEquals(setOf("крот", "станция", "неизвестное"), data.keys)
                assertFalse(checkNotNull(data["крот"]).familiar)
                assertTrue(checkNotNull(data["станция"]).familiar)
                assertTrue(checkNotNull(data["неизвестное"]).familiar)
            }
        }
        assertEquals(1, reads.get())
        val all = repository.hintWordData(listOf("кот", "крот", "колея", "станция"))
        assertEquals(listOf(false, false, true, true), all.values.map { it.familiar })
    }

    @Test
    fun missingOrDamagedFamiliarityUsesLengthRuleAndKeepsGameReady() = runTest {
        val readers: List<suspend () -> String?> = listOf(
            { null }, { throw IOException("Missing familiarity") }, { "2\n" }, { "2\nx\n" },
        )
        for (reader in readers) {
            val reads = AtomicInteger()
            val repository = WordRepository(readWords = { "колея\nстанция\n" }, readFamiliarity = {
                reads.incrementAndGet()
                reader()
            })
            withContext(Dispatchers.Main) {
                val game = repository.startGame("колеястанция")
                val current = CurrentCampaignLevel(CampaignLevel(1, game.word, 1, 1, emptyList()), game)
                val data = repository.hintWordData(game.allWords)
                assertTrue(data.values.all { it.familiar })
                assertEquals("станция", HintRules.next(current, data, random = Random(17)).state.hints.ladder.target?.word)
                assertEquals(data, repository.hintWordData(game.allWords))
            }
            assertEquals(1, reads.get())
            assertEquals(LoadState.Ready(2), repository.state.value)
        }
    }

    @Test
    fun cancellationDoesNotCacheUnknownFamiliarity() = runTest {
        var reads = 0
        val repository = WordRepository(readWords = { "кот" }, readFamiliarity = {
            if (++reads == 1) throw CancellationException("Cancelled read")
            "0"
        })
        try {
            repository.hintWordData(listOf("кот"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(checkNotNull(repository.hintWordData(listOf("кот"))["кот"]).familiar)
        assertEquals(2, reads)
    }

    @Test
    fun largestCampaignLevelBuildsOnlyLocalMapOffMain() = runTest {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val ioCalls = AtomicInteger()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.IO.dispatch(context, Runnable {
                    assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                    ioCalls.incrementAndGet()
                    block.run()
                })
            }
        }
        val repository = WordRepository(readWords = { readWords() }, ioDispatcher = io,
            readFamiliarity = { assets.open("word_familiarity.txt").bufferedReader().use { it.readText() } })
        val index = repository.requireIndex()
        val largest = withContext(Dispatchers.Default) {
            val levels = assets.open("campaign.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("levels") }
            (0 until levels.length()).map { index.findBuildable(levels.getJSONObject(it).getString("word")) }.maxBy { it.size }
        }
        val before = ioCalls.get()
        val data = withContext(Dispatchers.Main) { repository.hintWordData(largest) }
        assertTrue(ioCalls.get() > before)
        assertEquals(largest.toSet(), data.keys)
        val expected = withContext(Dispatchers.IO) {
            WordFamiliarity.fromText(index.words, assets.open("word_familiarity.txt").bufferedReader().use { it.readText() })
        }
        for ((word, metadata) in data) assertEquals(checkNotNull(expected.tier(word)) >= 2, metadata.familiar)
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
