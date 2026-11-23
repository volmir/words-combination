package com.example.sostavslova.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.InstallEvidence
import com.example.sostavslova.core.WelcomeState
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.di.AppContainer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Copy only the allowlisted file into a new installation's files directory. */
@RunWith(AndroidJUnit4::class)
class ProgressRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun restoredFileKeepsProgressAndWelcomeDone() = restore(changedCampaign = false)

    @Test fun restoredFileReconcilesChangedCampaignWithoutRepeatingWelcome() = restore(changedCampaign = true)

    private fun restore(changedCampaign: Boolean) = runTest {
        val oldFiles = temporary.newFolder("old-phone")
        val newFiles = temporary.newFolder("new-phone")
        val relativePath = "datastore/progress.json"
        val source = File(oldFiles, relativePath).also { it.parentFile?.mkdirs() }
        val words = WordRepository({ "карта\nкот\nрека\nрот\nторт\n" })
        val index = words.requireIndex()
        val campaign = requireNotNull(LevelsRepository(readCampaign = { miniCampaignJson }).getCampaign())
        val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saved = try {
            val store = PreferenceDataStoreFactory.create(scope = sourceScope) { source }
            val repository = ProgressRepository(
                store,
                installEvidence = { InstallEvidence.FRESH_INSTALL },
                hadLocalStateAtStartup = false,
            )
            assertEquals(WelcomeState.SHOW, repository.resolveWelcomeState())
            assertTrue(repository.markWelcomeDone())
            repository.migrateCampaign(campaign, index)
            assertEquals(
                AnswerResult.CORRECT,
                repository.submitCampaignAnswer(campaign, 1, "карта", index).result,
            )
            repository.awaitSaved()
            repository.observeCampaignProgress().first().also {
                assertEquals(listOf("карта"), it.levels.getValue("картотека").found)
                assertTrue(it.levels.getValue("картотека").completedTaskIds.isNotEmpty())
            }
        } finally {
            sourceScope.cancel()
            sourceScope.coroutineContext.job.join()
        }

        val destination = File(newFiles, relativePath).also { it.parentFile?.mkdirs() }
        source.copyTo(destination)
        assertEquals(listOf(relativePath), newFiles.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(newFiles).invariantSeparatorsPath }.toList())
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = newFiles
        }
        val restoredCampaignJson = if (changedCampaign) {
            miniCampaignJson.replace("\"трактор\"", "\"тракторка\"")
        } else miniCampaignJson
        // Use the production default path and startup detection, without a progressFile override.
        val container = AppContainer(context, readCampaign = { restoredCampaignJson }, words = words)
        try {
            val repository = container.progressRepository
            assertEquals(saved, repository.observeCampaignProgress().first())
            assertEquals(WelcomeState.DONE, repository.resolveWelcomeState())
            val restoredCampaign = requireNotNull(container.levelsRepository.getCampaign())
            val reconciled = repository.migrateCampaign(restoredCampaign, index)
            assertEquals(saved.levels.getValue("картотека"), reconciled.levels.getValue("картотека"))
            assertEquals(saved.highestUnlockedLevel, reconciled.highestUnlockedLevel)
            assertEquals(CampaignIdentity.of(restoredCampaign), reconciled.campaignId)
            if (changedCampaign) assertNotEquals(saved.campaignId, reconciled.campaignId)
            else assertEquals(saved, reconciled)
            assertEquals(reconciled, repository.migrateCampaign(restoredCampaign, index))
            assertEquals(WelcomeState.DONE, repository.resolveWelcomeState())
            assertFalse(repository.markWelcomeDone())
            container.awaitSaved()
        } finally {
            container.applicationScope.cancel()
            container.applicationScope.coroutineContext.job.join()
        }
    }
}
