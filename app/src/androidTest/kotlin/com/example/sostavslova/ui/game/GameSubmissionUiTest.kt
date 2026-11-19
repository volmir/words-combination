package com.example.sostavslova.ui.game

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.*
import com.example.sostavslova.data.*
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GameSubmissionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactDictionaryRejectionTravelsThroughRepositoryAndViewModelToAccessibleBanner() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "submission-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            val repository = ProgressRepository(PreferenceDataStoreFactory.create(scope = scope) {
                File(directory, "progress.json")
            })
            val words = WordRepository({ context.assets.open("words.txt").bufferedReader().use { it.readText() } })
            val levels = LevelsRepository({ error("No v1") }, readCampaign = {
                """{"version":2,"countMinLength":4,
                    "stages":[{"number":1,"firstLevel":1,"lastLevel":1}],
                    "levels":[{"number":1,"word":"вегетарианец","stage":1,"requiredStars":1,
                    "tasks":[{"id":"count","type":"T1","n":1}]}]}"""
            })
            val definitions = RiddlesRepository { error("No riddle task") }
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(owner, viewModelFactory { initializer {
                    GameViewModel(SavedStateHandle(mapOf("index" to "1")), words, levels, repository, definitions)
                } })[GameViewModel::class.java]
            }
            withContext(Dispatchers.Default) { withTimeout(10_000) { model.uiState.first { it.canPlay } } }
            withContext(Dispatchers.Main) { model.onDismissExplanations() }
            val before = repository.observeCampaignProgress().first()
            compose.setContent {
                val state by model.uiState.collectAsState()
                WordsTheme {
                    GameScreen(state, model::onInputChanged, model::onSubmit, {}, {}, {}, {})
                }
            }
            val expected = "Слово «бык» есть в словаре, но его нельзя составить из букв этого уровня. Не хватает буквы «Б»: есть 0, нужно 1"
            repeat(2) {
                compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("бык")
                compose.onNode(hasSetTextAction()).performImeAction()
                compose.waitUntil(10_000) { !model.uiState.value.busy && model.uiState.value.message != null }
                compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(expected).assert(hasAnyAncestor(hasTestTag("game_feedback")))
                compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
                    and (hasText(expected) or hasAnyDescendant(hasText(expected)))).assertExists()
                // Отказ не стирает набранное слово: его можно исправить без повторного набора.
                compose.onNode(hasSetTextAction()).assertIsEnabled().assertTextEquals("бык")
                assertTrue(model.uiState.value.canPlay)
                assertEquals(before, repository.observeCampaignProgress().first())
            }
        } finally {
            withContext(Dispatchers.Main) { owner.viewModelStore.clear() }
            scope.cancel()
            scope.coroutineContext.job.join()
            directory.deleteRecursively()
        }
    }

    @Test fun starNoticeUsesPluralAndKeepsOpenedLevelInOneBanner() {
        val game = GameState("блокти", listOf("кот"))
        val initial = GameUiState(loading = false, game = game, statistics = game.statistics())
        val notice = mutableStateOf<GameMessage?>(null)
        compose.setContent {
            WordsTheme { GameScreen(initial, {}, {}, {}, {}, {}, {}, notice = notice.value) }
        }
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        fun show(message: GameMessage, expected: String) {
            compose.runOnIdle { notice.value = message }
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        }
        show(GameMessage(R.string.star_received,
            pluralText = R.plurals.stars_received, count = 1),
            resources.getQuantityString(R.plurals.stars_received, 1, 1))
        show(GameMessage(R.string.star_received,
            pluralText = R.plurals.stars_received, count = 2),
            resources.getQuantityString(R.plurals.stars_received, 2, 2))
        show(GameMessage(R.string.star_received,
            pluralText = R.plurals.stars_received_next_level, count = 2),
            resources.getQuantityString(R.plurals.stars_received_next_level, 2, 2))
    }

    @Test fun rejectionMessagesKeepCategoriesCountsAndSafeFallback() {
        val game = GameState("блокти", listOf("кот"))
        val index = WordIndex(listOf("кот", "колокол", "блок"))
        val initial = GameUiState(loading = false, game = game, statistics = game.statistics())
        val state = mutableStateOf(initial)
        compose.setContent { WordsTheme { GameScreen(state.value, {}, {}, {}, {}, {}, {}) } }
        fun show(submission: Submission, answer: String) {
            compose.runOnIdle { state.value = initial.copy(message = submissionMessage(submission, answer)) }
        }
        fun checkText(text: String) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        // Составимое слово из точного словаря, исключённое из allWords, не получает зачёт.
        val notCounted = game.submit("блок", index)
        assertEquals(AnswerResult.NOT_COUNTED, notCounted.result)
        show(notCounted, "блок")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        checkText(context.getString(R.string.answer_not_counted, "блок"))
        val missing = game.submit("колокол", index)
        assertEquals(MissingLetter('к', 1, 2), missing.missingLetter)
        show(missing, "колокол")
        checkText("Слово «колокол» есть в словаре, но его нельзя составить из букв этого уровня. Не хватает буквы «К»: есть 1, нужно 2")
        show(missing.copy(probabilistic = true), "колокол")
        checkText("Слово «колокол» нельзя составить из букв этого уровня. Не хватает буквы «К»: есть 1, нужно 2")
        compose.onNodeWithText("есть в словаре", substring = true).assertDoesNotExist()
        show(missing.copy(missingLetter = null), "колокол")
        checkText(context.getString(R.string.answer_letters_missing_error))
        show(game.submit("ыыыыы", index), "ыыыыы")
        checkText(context.getString(R.string.answer_unknown, "ыыыыы"))
        show(game.submit("кот", index), "кот")
        checkText(context.getString(R.string.correct_answer))
        show(game.submit("кот", index).state.submit("кот", index), "кот")
        checkText(context.getString(R.string.answer_already_found, "кот"))
        show(game.submit("ко", index), "ко")
        checkText(context.getString(R.string.word_too_short))
        show(game.submit(game.word, index), game.word)
        checkText(context.getString(R.string.answer_same_as_source))
    }
}
