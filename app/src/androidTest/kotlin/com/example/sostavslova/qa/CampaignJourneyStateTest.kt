package com.example.sostavslova.qa

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sostavslova.R
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.di.AppContainer
import com.example.sostavslova.ui.game.GameUiState
import com.example.sostavslova.ui.game.GameViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampaignJourneyStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val containers = mutableListOf<AppContainer>()
    private val owners = mutableListOf<ViewModelStoreOwner>()

    private fun container(name: String, words: WordRepository? = null) =
        QaCampaign.container(File(temporary.root, "$name.preferences_pb"), words = words)
            .also { containers.add(it) }

    @After fun close() = runTest {
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        containers.forEach { it.closeQa() }
    }

    @Test fun differentTaskCombinationsUnlockAndAllT6MembersCountWithoutDatabaseOrFilter() = runTest {
        for ((number, route) in listOf(
            listOf("кот", "кот", "кот", "крот"),
            listOf("крот", "трек", "кот", "рак"),
        ).withIndex()) {
            val app = QaCampaign.container(File(temporary.root, "route$number.preferences_pb"),
                QaCampaign.Meanings.ABSENT).also { containers.add(it) }
            val campaign = requireNotNull(app.levelsRepository.getCampaign())
            val index = app.wordRepository.requireIndex()
            val progress = app.progressRepository
            progress.migrateCampaign(campaign, index)
            suspend fun answer(level: Int, word: String) =
                progress.submitCampaignAnswer(campaign, level, word, index) // knownWords = null.
            suspend fun saved() = progress.observeCampaignProgress().first()
            assertEquals(AnswerResult.CORRECT, answer(1, route[0]).result)
            assertEquals(setOf(if (number == 0) "riddle1" else "initial1"),
                saved().levels.getValue("картотека").completedTaskIds)
            assertEquals(2, saved().highestUnlockedLevel)
            assertEquals(AnswerResult.CORRECT, answer(2, route[1]).result)
            assertEquals(setOf(if (number == 0) "ending2" else "long2"),
                saved().levels.getValue("электротрактор").completedTaskIds)
            assertEquals(3, saved().highestUnlockedLevel)
            assertEquals(AnswerResult.CORRECT, answer(3, route[2]).result)
            val beforeRepeat = saved()
            assertEquals(AnswerResult.ALREADY_FOUND, answer(3, route[2]).result)
            assertEquals(beforeRepeat, saved()) // Повтор не заменяет второе слово T6.
            assertFalse(saved().finaleReached)
            assertEquals(AnswerResult.CORRECT, answer(3, route[3]).result)
            assertEquals(setOf("theme3", "contains3"), saved().levels.getValue("электрокарта").completedTaskIds)
            assertTrue(saved().finaleReached)
            assertFalse(CampaignRules.summary(campaign, saved()).isMaster)
            val beforeInvalid = saved()
            assertEquals(AnswerResult.NOT_FOUND, answer(3, "тара").result) // Буквы есть, слова в словаре нет.
            assertEquals(beforeInvalid, saved())
        }
    }

    private suspend fun model(app: AppContainer): GameViewModel = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            .also { owners.add(it) }
        ViewModelProvider(owner, viewModelFactory { initializer {
            GameViewModel(SavedStateHandle(mapOf("index" to "1")), app.wordRepository,
                app.levelsRepository, app.progressRepository, app.definitionsRepository)
        } })[GameViewModel::class.java]
    }

    private suspend fun GameViewModel.await(predicate: (GameUiState) -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { uiState.first(predicate) }
    }

    @Test fun openingAndAnswerMeasureDictionaryAccessAndNeverScanOnUiThread() = runTest {
        // 200 тысяч уникальных синтетических слов, не составимых из исходника.
        val alphabet = "абвгдежзийклмнопрстуфхцчшщэюя"
        val filler = List(200_000) { value ->
            var remaining = value
            buildString {
                append('я')
                repeat(4) { append(alphabet[remaining % alphabet.length]); remaining /= alphabet.length }
            }
        }
        // Составимые слова в хвосте: проверяем, что поиск дошёл за все 200 тысяч масок.
        val probe = DictionaryProbe(filler + QaCampaign.words)
        val words = WordRepository(readWords = { "synthetic" }, indexFactory = { probe.index() })
        val app = container("performance", words)
        // Прогрев индекса исключает из замера чтение файла и построение индекса.
        withContext(Dispatchers.Default) { words.requireIndex().contains("кот") }
        probe.reads.set(0); probe.mainReads.set(0); probe.lastReadIndex.set(-1)
        val started = SystemClock.elapsedRealtimeNanos()
        val vm = model(app)
        vm.await { it.canPlay || it.loadError }
        val opened = SystemClock.elapsedRealtimeNanos()
        assertFalse(vm.uiState.value.loadError)
        assertTrue("Search must reach buildable words after 200,000 entries", probe.lastReadIndex.get() >= filler.size)
        assertEquals("Opening traversed dictionary on Main", 0, probe.mainReads.get())
        probe.reads.set(0)
        withContext(Dispatchers.Main) { vm.onInputChanged("крот"); vm.onSubmit() }
        val answered = vm.await { !it.busy && it.message != null }
        val finished = SystemClock.elapsedRealtimeNanos()
        assertEquals(R.string.correct_answer, answered.message?.text)
        assertEquals(listOf("крот"), answered.game?.found)
        assertEquals("Answer traversed dictionary on Main", 0, probe.mainReads.get())
        Log.i("QA-01", "open_ms=${(opened - started) / 1_000_000.0}, " +
            "answer_ms=${(finished - opened) / 1_000_000.0}, " +
            "answer_dictionary_reads=${probe.reads.get()}, main_dictionary_reads=${probe.mainReads.get()}")
        // Повторный ON_RESUME с готовой сессией не должен снова перебирать словарь.
        val beforeResume = probe.reads.get()
        withContext(Dispatchers.Main) { repeat(5) { vm.onGameOpened() } }
        assertEquals(beforeResume, probe.reads.get())
        assertEquals(0, probe.mainReads.get())
    }
}
