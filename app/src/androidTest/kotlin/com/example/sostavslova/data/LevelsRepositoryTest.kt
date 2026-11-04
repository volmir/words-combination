package com.example.sostavslova.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.Alphabet
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
