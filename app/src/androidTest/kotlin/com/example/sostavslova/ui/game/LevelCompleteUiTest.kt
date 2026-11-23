package com.example.sostavslova.ui.game

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LevelCompleteUiTest {
    // Весь диалог проверяется в окне 320dp, включая fontScale 2.0.
    @get:Rule val compose = createComposeRule()
    private val game = GameState("картотека", listOf("карта", "крот", "кот"), listOf("карта"))
    private val current = CurrentCampaignLevel(CampaignLevel(1, game.word, 1, 1,
        listOf(CampaignTask.T1("count", 1), CampaignTask.T2("initial", 'к', 1))), game, setOf("count"))
    private val summary = requireNotNull(LevelCompleteSummary.build(current,
        CampaignLevelProgress(completedTaskIds = current.completedTaskIds,
            achievements = setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT)), 15, true,
        setOf(LevelAchievement.NO_HINTS)))
    private val initial = GameUiState(loading = false, game = game, campaignLevel = current,
        levelComplete = LevelComplete(summary), totalLevels = 15, nextLevelNumber = 2)

    @Test fun stayClosesDialogAndNextUsesNavigationCallback() {
        val state = mutableStateOf(initial)
        var next = false
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, {}, onCloseLevelComplete = { openNext ->
                    next = openNext
                    state.value = state.value.copy(levelComplete = null)
                })
            }
        }
        compose.onNodeWithTag("level_complete_title").assertTextEquals("Уровень 1 пройден!")
        compose.onNodeWithTag("level_complete_stars").assertTextEquals("Звёзд: 1 из 2")
        compose.onNodeWithTag("level_complete_achievements").assertTextEquals("Достижения: 2 из 5")
        compose.onNodeWithTag("level_complete_achievement_no_hints")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Новое достижение"))
        compose.onNodeWithTag("level_complete_stay").performScrollTo().performClick()
        compose.onNodeWithTag("level_complete_dialog").assertDoesNotExist()
        compose.onNodeWithTag("game_screen").assertExists()
        assertFalse(next)
        compose.runOnIdle { state.value = initial }
        compose.onNodeWithTag("level_complete_next").performScrollTo().performClick()
        assertTrue(next)
        compose.onNodeWithTag("level_complete_dialog").assertDoesNotExist()
    }

    @Test fun outsideTapActsAsStay() {
        val state = mutableStateOf(initial)
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, {}, onCloseLevelComplete = { next ->
                    assertFalse(next)
                    state.value = state.value.copy(levelComplete = null)
                })
            }
        }
        compose.onNode(isDialog()).performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, 1f)) }
        compose.onNodeWithTag("level_complete_dialog").assertDoesNotExist()
    }

    @Test fun textAndTargetsFit320DpAtDoubleFontScaleWithAllStars() {
        compose.setContent {
            val base = LocalDensity.current
            // Меняем плотность окна так, чтобы его доступная ширина составляла 320dp.
            val widthPx = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * base.density
            CompositionLocalProvider(LocalDensity provides Density(widthPx / 320f, 2f)) {
                WordsTheme {
                    GameScreen(initial.copy(levelComplete = LevelComplete(summary.copy(stars = 2))), {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("level_complete_stars").performScrollTo().assertTextEquals("Звёзд: 2 из 2")
        compose.onNodeWithText("Все звёзды получены").performScrollTo().assertIsDisplayed()
        for (tag in listOf("level_complete_next", "level_complete_stay")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            // Плотность изменена локально, используем ширину самого диалога (272dp).
            val dialog = compose.onNodeWithTag("level_complete_dialog").fetchSemanticsNode()
            val minPx = dialog.size.width / 272f * 48f - 1f
            assertTrue(node.size.width >= minPx && node.size.height >= minPx)
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
            hasAnyAncestor(hasTestTag("level_complete_dialog")), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach { assertTextLayoutFits(it, "level complete 320dp fontScale=2") }
            }
    }
}
