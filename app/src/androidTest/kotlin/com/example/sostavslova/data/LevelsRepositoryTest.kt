package com.example.sostavslova.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.CampaignValidator
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.TaskType
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LevelsRepositoryTest {
    @Test
    fun miniV2IsValidatedAndCachedAcrossConcurrentMainCallers() = runTest {
        val reads = AtomicInteger()
        val legacyReads = AtomicInteger()
        val repository = LevelsRepository(
            readLevels = { legacyReads.incrementAndGet(); error("Must not read v1 for v2") },
            readCampaign = {
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                reads.incrementAndGet()
                miniCampaignJson
            },
        )
        val batches = withContext(Dispatchers.Main) {
            List(6) { async { repository.getCampaign() } }.awaitAll()
        }
        val campaign = requireNotNull(repository.getCampaign())
        assertEquals(1, reads.get())
        assertEquals(0, legacyReads.get())
        assertTrue(batches.all { it === campaign })
        assertEquals(2, campaign.levels.size)
        assertEquals(4, campaign.countMinLength)
        assertEquals(7, campaign.maxStars)
        assertTrue(CampaignValidator.validate(campaign).isEmpty())
        assertEquals(TaskType.entries.toSet(), campaign.levels.flatMap { it.tasks }.map { it.type }.toSet())
        assertEquals(listOf("рот", "торт"), (campaign.levels.last().tasks.first() as CampaignTask.T6).words)
    }

    @Test
    fun missingV2IsExplicitAndCached() = runTest {
        val reads = AtomicInteger()
        val repository = LevelsRepository(readLevels = { "[]" }, readCampaign = { reads.incrementAndGet(); null })
        repeat(2) { assertNull(repository.getCampaign()) }
        assertEquals(1, reads.get())
        assertTrue(repository.getLevels().isEmpty())
    }

    @Test
    fun invalidV2NeverFallsBackToV1OrCachesFailure() = runTest {
        val invalid = listOf(
            miniCampaignJson.replace("\"requiredStars\": 2", "\"requiredStars\": 6"),
            miniCampaignJson.replace("\"requiredStars\": 2", "\"requiredStars\": 0"),
            miniCampaignJson.replace("\"T1\"", "\"T99\""),
            miniCampaignJson.dropLast(1),
            miniCampaignJson + " trailing",
            "",
        )
        for (text in invalid) {
            var current = text
            val repository = LevelsRepository(
                readLevels = { error("Corrupt v2 must not fall back to v1") },
                readCampaign = { current },
            )
            try {
                repository.getCampaign()
                fail("Expected invalid v2 to be rejected")
            } catch (_: IllegalArgumentException) {
                // Includes strict JSON decoding and core structural validation failures.
            }
            current = miniCampaignJson
            assertNotNull(repository.getCampaign())
        }
    }

    @Test
    fun unreadableV2PropagatesInsteadOfBecomingAbsent() = runTest {
        val repository = LevelsRepository(readLevels = { error("No fallback") }, readCampaign = { throw IOException("read failure") })
        try {
            repository.getCampaign()
            fail("Expected IO failure")
        } catch (error: IOException) {
            assertEquals("read failure", error.message)
        }
    }

    @Test
    fun loadsThirtyCoreLevelsFromRealAssetsOnceOffMain() = runTest {
        val reads = AtomicInteger()
        val repository = LevelsRepository(readLevels = {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            reads.incrementAndGet()
            InstrumentationRegistry.getInstrumentation().targetContext.assets
                .open("levels.json").bufferedReader().use { it.readText() }
        })
        val batches = withContext(Dispatchers.Main) {
            List(6) { async { repository.getLevels() } }.awaitAll()
        }
        val levels = repository.getLevels()
        assertEquals(1, reads.get())
        assertEquals(30, levels.size)
        assertTrue(levels.all { Alphabet.indexOf(it.letter) >= 0 })
        assertEquals('с', levels.single { it.word == "реввоенсовет" }.letter)
        assertEquals("рекуперация", levels.first().word)
        assertTrue(batches.all { it == levels })
    }
}
