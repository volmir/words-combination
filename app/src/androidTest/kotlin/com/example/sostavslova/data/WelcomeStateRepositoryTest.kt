package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.InstallEvidence
import com.example.sostavslova.core.WelcomeState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.FileSystem
import okio.Path.Companion.toPath

/** Отдельный ключ приветствия на настоящем Preferences DataStore. */
class WelcomeStateRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: ProgressRepository
    private var evidence = InstallEvidence.UNKNOWN
    private var hadLocalState = true
    private val welcome = stringPreferencesKey("welcomeStateV1")
    private val campaignKey = stringPreferencesKey("campaignProgressV2")
    private val legacyProgress = stringPreferencesKey("levelProgress")
    private val legacyAnswers = stringPreferencesKey("levelAnswers")

    @Before fun setUp() {
        directory = temporaryFolder.newFolder()
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                File(directory, "welcome.preferences_pb").absolutePath.toPath()
            },
        )
        repository = ProgressRepository(
            dataStore = store,
            installEvidence = { evidence },
            hadLocalStateAtStartup = hadLocalState,
        )
    }

    private suspend fun restart(
        install: InstallEvidence = evidence,
        localState: Boolean = hadLocalState,
    ) {
        scope.cancel()
        scope.coroutineContext.job.join()
        evidence = install
        hadLocalState = localState
        openStore()
    }

    @After fun tearDown() = runTest {
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    @Test fun freshInstallPersistsShowUntilShownAndDoneSurvivesRestart() = runTest {
        restart(InstallEvidence.FRESH_INSTALL, localState = false)
        assertEquals(WelcomeState.SHOW, repository.resolveWelcomeState())
        assertEquals(WelcomeState.SHOW.name, store.data.first()[welcome])
        assertTrue(repository.markWelcomeDone())
        assertEquals(WelcomeState.DONE.name, store.data.first()[welcome])
        assertFalse(repository.markWelcomeDone())
        restart(localState = true)
        assertEquals(WelcomeState.DONE, repository.resolveWelcomeState())
        assertFalse(repository.markWelcomeDone())
    }

    @Test fun existingInstallWithoutWelcomeKeyDoesNotGetShowOrWriteIt() = runTest {
        restart(InstallEvidence.EXISTING_INSTALL, localState = false)
        assertEquals(WelcomeState.UNKNOWN, repository.resolveWelcomeState())
        assertNull(store.data.first()[welcome])
    }

    @Test fun unknownInstallEvidenceFailsClosedWithoutWritingWelcomeKey() = runTest {
        restart(InstallEvidence.UNKNOWN, localState = false)
        assertEquals(WelcomeState.UNKNOWN, repository.resolveWelcomeState())
        assertNull(store.data.first()[welcome])
    }

    @Test fun doneCannotBeMarkedWithoutConfirmedShow() = runTest {
        restart(InstallEvidence.EXISTING_INSTALL, localState = false)
        assertFalse(repository.markWelcomeDone())
        assertNull(store.data.first()[welcome])
    }

    @Test fun upgradeWithLegacyProgressFailsClosedAndKeepsItUntouched() = runTest {
        val progress = """{"рекуперация":{"completed":true}}"""
        val answers = """{"рекуперация":["каре"]}"""
        store.edit {
            it[legacyProgress] = progress
            it[legacyAnswers] = answers
        }
        restart(InstallEvidence.FRESH_INSTALL, localState = true)
        assertEquals(WelcomeState.UNKNOWN, repository.resolveWelcomeState())
        assertNull(store.data.first()[welcome])
        assertEquals(progress, store.data.first()[legacyProgress])
        assertEquals(answers, store.data.first()[legacyAnswers])
    }

    @Test fun resolvingWelcomeKeepsUnidentifiedCampaignProgressAndStars() = runTest {
        val raw = """{"version":2,"highestUnlockedLevel":2,"levels":""" +
            """{"рекуперация":{"found":["каре"],"completedTaskIds":["count","initial"]}}}"""
        store.edit { it[campaignKey] = raw }
        // Обновление с прежней short15-сборки: campaignId ещё нет, приветствие не показываем.
        restart(InstallEvidence.EXISTING_INSTALL, localState = true)
        assertEquals(WelcomeState.UNKNOWN, repository.resolveWelcomeState())
        assertNull(store.data.first()[welcome])
        assertEquals(raw, store.data.first()[campaignKey])
        val saved = repository.observeCampaignProgress().first()
        assertNull(saved.campaignId)
        val level = CampaignLevel(
            1, "рекуперация", 1, 2,
            listOf(CampaignTask.T1("count", 1), CampaignTask.T2("initial", 'к', 1)),
        )
        assertEquals(2, CampaignRules.stars(level, saved))
    }

    @Test fun concurrentDoneMarksAreAtomicAndOnlyFirstChangesState() = runTest {
        restart(InstallEvidence.FRESH_INSTALL, localState = false)
        assertEquals(WelcomeState.SHOW, repository.resolveWelcomeState())
        val results = coroutineScope {
            (1..8).map { async { repository.markWelcomeDone() } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        assertEquals(WelcomeState.DONE.name, store.data.first()[welcome])
    }
}
