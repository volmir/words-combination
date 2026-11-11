package com.example.sostavslova.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.data.LoadState
import com.example.sostavslova.data.miniCampaignJson
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContainerTest {
    @Test
    fun miniCampaignCanBeInjectedThroughContainer() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(context, readCampaign = { miniCampaignJson })
        try {
            val campaign = requireNotNull(container.levelsRepository.getCampaign())
            assertEquals(2, campaign.levels.size)
            assertSame(campaign, container.levelsRepository.getCampaign())
        } finally {
            container.applicationScope.cancel()
        }
    }

    @Test
    fun corruptInjectedCampaignIsNotHiddenByLegacyAssets() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (text in listOf("{", miniCampaignJson.replace("\"T1\"", "\"UNKNOWN\""),
            miniCampaignJson.replace("\"requiredStars\": 2", "\"requiredStars\": 6"))) {
            val container = AppContainer(context, readCampaign = { text })
            try {
                try {
                    container.levelsRepository.getCampaign()
                    fail("Expected invalid campaign")
                } catch (_: IllegalArgumentException) {
                    // Presence of valid v1 assets must not hide broken v2.
                }
            } finally {
                container.applicationScope.cancel()
            }
        }
    }

    @Test
    fun applicationOwnsOneContainerAndOneRepositoryOfEachKind() = runTest {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication
        val container = application.container
        assertSame(container, application.container)
        assertSame(container.wordRepository, application.container.wordRepository)
        assertSame(container.levelsRepository, application.container.levelsRepository)
        assertSame(container.definitionsRepository, application.container.definitionsRepository)
        assertSame(container.progressRepository, application.container.progressRepository)
        assertNull(container.levelsRepository.getCampaign())
        assertEquals(30, container.levelsRepository.getLevels().size)
        container.wordRepository.ensureLoaded()
        assertTrue((container.wordRepository.state.value as LoadState.Ready).wordCount > 0)
    }
}
