package com.example.sostavslova.qa

import android.app.Activity
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.TaskType
import com.example.sostavslova.di.AppContainer
import com.example.sostavslova.di.LocalAppContainer
import com.example.sostavslova.nav.WordsNavHost
import com.example.sostavslova.ui.WordsApp
import com.example.sostavslova.ui.findActivity
import com.example.sostavslova.ui.theme.WordsTheme
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
class CampaignJourneyUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val mounted = mutableStateOf<AppContainer?>(null)
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val containers = mutableListOf<AppContainer>()
    private lateinit var container: AppContainer
    private lateinit var file: File
    private lateinit var nav: NavHostController
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var activity: Activity

    private fun begin(meanings: QaCampaign.Meanings = QaCampaign.Meanings.SAFE, fullApp: Boolean = false) {
        file = File(temporary.root, "qa.preferences_pb")
        container = QaCampaign.container(file, meanings).also { containers.add(it) }
        mounted.value = container
        compose.setContent {
            mounted.value?.let { current -> key(current) {
                val hostOwner = requireNotNull(LocalViewModelStoreOwner.current)
                val owner = androidx.compose.runtime.remember {
                    object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                        .also { owners.add(it) }
                }
                CompositionLocalProvider(LocalAppContainer provides current,
                    LocalViewModelStoreOwner provides if (fullApp) hostOwner else owner) {
                    val controller = rememberNavController()
                    val dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                    val host = requireNotNull(LocalContext.current.findActivity())
                    SideEffect { nav = controller; back = dispatcher; activity = host }
                    WordsTheme { if (fullApp) WordsApp() else WordsNavHost(controller) }
                }
            } }
        }
        awaitTag("level_card_1")
    }

    @After fun close() = runTest {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mounted.value = null
            owners.forEach { it.viewModelStore.clear() }
        }
        containers.forEach { it.closeQa() }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private suspend fun saved(): CampaignProgress = withContext(Dispatchers.Default) {
        container.progressRepository.observeCampaignProgress().first()
    }
    private fun dismissExplanation() {
        if (compose.onAllNodesWithText("Новые задания этапа").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        }
    }
    private fun open(number: Int, explanationExpected: Boolean? = null) {
        compose.onNodeWithTag("levels_grid").performScrollToNode(hasTestTag("level_card_$number"))
        compose.onNodeWithTag("level_card_$number").assertIsEnabled().performClick()
        awaitTag("level_header")
        when (explanationExpected) {
            true -> compose.onNodeWithText("Новые задания этапа").assertExists()
            false -> compose.onNodeWithText("Новые задания этапа").assertDoesNotExist()
            null -> Unit
        }
        dismissExplanation()
        compose.onNode(hasSetTextAction()).performScrollTo().assertIsEnabled()
    }
    private fun toMap() {
        compose.runOnUiThread { back.onBackPressed() }
        awaitTag("levels_screen")
    }
    private suspend fun answer(word: String, count: Int) {
        enter(word)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                container.progressRepository.observeCampaignProgress().first {
                    it.levels.values.sumOf { level -> level.found.size } == count
                }
            }
        }
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsEnabled() }.isSuccess
        }
    }
    private fun enter(word: String) {
        val input = compose.onNode(hasSetTextAction()).performScrollTo()
        input.performTextReplacement(word)
        input.performImeAction()
    }
    private suspend fun restart() {
        val old = container
        compose.runOnIdle { mounted.value = null; owners.forEach { it.viewModelStore.clear() } }
        compose.waitForIdle()
        old.closeQa()
        container = QaCampaign.container(file).also { containers.add(it) }
        assertNotSame(old.progressRepository, container.progressRepository)
        compose.runOnIdle { mounted.value = container }
        awaitTag("level_card_1")
    }

    @Test fun newInstallToFinaleRestartAndAllStars() = runTest {
        begin()
        assertEquals(1, saved().highestUnlockedLevel)
        val clean = saved()
        for (number in 2..3) {
            compose.onNodeWithTag("levels_grid").performScrollToNode(hasTestTag("level_card_$number"))
            compose.onNodeWithTag("level_card_$number")
                .assertTextContains("Уровень недоступен", substring = true).performClick()
            compose.onNodeWithText("Для открытия наберите на уровне ${number - 1}", substring = true).assertExists()
            compose.onNodeWithTag("game_screen").assertDoesNotExist()
            compose.onNodeWithText("Закрыть").performClick()
            assertEquals(clean, saved())
        }
        open(1, explanationExpected = true)
        assertTrue(setOf(TaskType.T1, TaskType.T2, TaskType.T4).all { it in saved().seenExplanations })
        repeat(4) { step ->
            compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
            withContext(Dispatchers.Default) { withTimeout(10_000) {
                container.progressRepository.observeCampaignProgress().first {
                    it.levels["картотека"]?.hints?.shownSteps == step + 1
                }
            } }
            compose.waitForIdle()
        }
        val hinted = saved()
        val hints = hinted.levels.getValue("картотека").hints
        assertEquals("кот", hints.ladder.target?.word)
        assertEquals(1, hints.paidUnits)
        restart()
        assertEquals(hinted, saved())
        open(1, explanationExpected = false)
        compose.onNodeWithTag("game_hint_stage").performScrollTo().assertTextEquals("Ступень 4 из 5")
        val campaign = requireNotNull(container.levelsRepository.getCampaign())
        val current = requireNotNull(CampaignRules.openLevel(campaign, saved(), 1, container.wordRepository.requireIndex()))
        assertEquals(4, HintRules.budget(current).remaining)
        answer("кот", 1)
        val first = saved().levels.getValue("картотека")
        assertEquals(setOf("riddle1"), first.completedTaskIds) // Три буквы не продвигают T1/T2.
        toMap(); open(2)
        answer("кот", 2) // Общий ответ на другом уровне разрешён.
        assertEquals(3, saved().highestUnlockedLevel)
        toMap(); open(1)
        answer("крот", 3); answer("торт", 4)
        assertEquals(3, saved().levels.getValue("картотека").completedTaskIds.size)
        toMap(); open(2); answer("трек", 5)
        toMap(); open(3); answer("кот", 6)
        assertFalse(saved().finaleReached)
        // Финальный ответ открывает модальный диалог и отключает ввод.
        enter("крот")
        awaitTag("level_finale_dialog")
        val pending = saved()
        assertEquals(2, pending.levels.getValue("электрокарта").completedTaskIds.size)
        assertTrue(pending.finaleReached); assertFalse(pending.finaleShown)
        assertFalse(CampaignRules.summary(campaign, pending).isMaster)
        restart()
        assertEquals(pending, saved())
        openFinaleLevel()
        compose.onNodeWithText("Звёзды: 7 из 8").assertExists()
        compose.onNodeWithText("Продолжить собирать звёзды").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_finale_dialog").fetchSemanticsNodes().isEmpty() }
        answer("карта", 8); answer("река", 9)
        val master = saved()
        assertTrue(CampaignRules.summary(campaign, master).isMaster)
        enter("крот")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Вы уже разгадали слово крот").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(master, saved())
        compose.onNodeWithTag("level_finale_dialog").assertDoesNotExist()
        toMap()
        compose.onNodeWithTag("levels_grid").performScrollToNode(hasTestTag("levels_finale_banner"))
        compose.onNodeWithTag("levels_finale_banner").assertTextEquals("Мастер — все звёзды собраны!")
        restart()
        assertEquals(master, saved())
        open(3)
        compose.onNodeWithTag("level_finale_dialog").assertDoesNotExist()
    }

    private fun openFinaleLevel() {
        compose.onNodeWithTag("levels_grid").performScrollToNode(hasTestTag("level_card_3"))
        compose.onNodeWithTag("level_card_3").performClick()
        awaitTag("level_finale_dialog")
    }

    @Test fun closedDeepLinkUsesInjectedCampaignWithoutChangingProgress() = runTest {
        begin()
        val before = saved()
        compose.runOnIdle { nav.navigate(Uri.parse("sostavslova://level/3")) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Этот уровень ещё закрыт. Пройдите предыдущий уровень.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game_screen").assertDoesNotExist()
        assertEquals(before, saved())
        compose.runOnIdle { nav.navigate(Uri.parse("sostavslova://level/1")) }
        awaitTag("level_header"); dismissExplanation()
        answer("кот", 1)
    }

    @Test fun t4RemainsPlayableWithoutDatabase() = fallback(QaCampaign.Meanings.ABSENT)
    @Test fun t4RemainsPlayableWhenEveryDefinitionIsFiltered() = fallback(QaCampaign.Meanings.FILTERED)

    private fun fallback(meanings: QaCampaign.Meanings) = runTest {
        begin(meanings); open(1)
        compose.onNodeWithText("Слово из 3 букв, начинается на «К»").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("кот").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsEnabled().performClick()
        awaitTag("game_hint_stage")
        val hints = saved().levels.getValue("картотека").hints
        assertEquals(0, hints.paidUnits)
        answer("кот", 1)
        assertEquals(setOf("riddle1"), saved().levels.getValue("картотека").completedTaskIds)
    }

    @Test fun exitFromMenuWaitsForSaveAndClosesHost() = runTest {
        begin(fullApp = true); open(1); answer("кот", 1)
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithTag("drawer_exit").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Прогресс сохранён.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("exit_dialog_confirm").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { activity.isFinishing }
        assertEquals(listOf("кот"), saved().levels.getValue("картотека").found)
    }

    @Test fun doubleSystemBackFromMapExitsWithSavedAnswers() = runTest {
        begin(fullApp = true); open(1); answer("кот", 1); toMap()
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithText("Нажмите «Назад» ещё раз, чтобы выйти").assertIsDisplayed()
        assertFalse(activity.isFinishing)
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitUntil(10_000) { activity.isFinishing }
        assertEquals(listOf("кот"), saved().levels.getValue("картотека").found)
    }
}
