package com.example.sostavslova.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.data.LoadState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContainerTest {
    @Test
    fun applicationOwnsOneContainerAndOneRepositoryOfEachKind() = runTest {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication
        val container = application.container
        assertSame(container, application.container)
        assertSame(container.wordRepository, application.container.wordRepository)
        assertSame(container.levelsRepository, application.container.levelsRepository)
        assertSame(container.definitionsRepository, application.container.definitionsRepository)
        assertSame(container.progressRepository, application.container.progressRepository)
        container.wordRepository.ensureLoaded()
        assertTrue((container.wordRepository.state.value as LoadState.Ready).wordCount > 0)
    }
}
