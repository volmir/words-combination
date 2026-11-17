package com.example.sostavslova.ui.time

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val campaign = Campaign((1..6).map { stage ->
        val first = listOf(1, 4, 7, 10, 12, 14)[stage - 1]
        Stage(stage, first, if (stage == 6) 15 else listOf(3, 6, 9, 11, 13)[stage - 1])
    }, (1..15).map { number ->
        CampaignLevel(number, "достопримечательность", when (number) {
            in 1..3 -> 1; in 4..6 -> 2; in 7..9 -> 3; in 10..11 -> 4; in 12..13 -> 5; else -> 6
        }, 1, listOf(CampaignTask.T1("r", 1), CampaignTask.T1("m", 2)))
    }, contentSha256 = "a".repeat(64))

    private fun ready(ms: Long? = null): TimeUiState = TimeUiState(loading = false, campaign = campaign,
        summary = CampaignTimeStatistics.aggregate(campaign, CampaignTimeSnapshot(requireNotNull(campaign.contentSha256),
            if (ms == null) emptyMap() else mapOf(1 to LevelTimeMeasurement("достопримечательность", ms, true, ms, null)))))

    @Test fun localSnapshotChangesAppearWithoutRefreshAndMissingValuesAreExplicit() {
        val state = mutableStateOf(ready().copy(historicalLevels = setOf(2)))
        compose.setContent { WordsTheme { TimeScreen(state.value) } }
        compose.onNodeWithTag("time_total").assertTextEquals("Общее активное время: 0 ч 0 мин 0 с")
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1").assertTextContains("Активное время: Ещё не играл: 0 ч 0 мин 0 с")
        compose.onNodeWithTag("time_screen").performScrollToKey("level_2")
        compose.onNodeWithTag("time_level_2").assertTextContains("Активное время: Уже играл до включения измерения: время неизвестно")
        compose.runOnIdle { state.value = ready(97_385_000) }
        compose.onNodeWithTag("time_screen").performScrollToIndex(1)
        compose.onNodeWithTag("time_total").assertTextEquals("Общее активное время: 27 ч 3 мин 5 с")
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1").assertTextContains("До R звёзд (R = 1): 27 ч 3 мин 5 с")
            .assertTextContains("До всех M звёзд (M = 2): Время отсутствует").assertHasNoClickAction()
    }

    @Test fun allFieldsFitNativeScreenAndKeepTalkBackHeadings() {
        compose.setContent {
            WordsTheme { TimeScreen(ready(Long.MAX_VALUE)) }
        }
        compose.onNodeWithText("Время игры").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        for (stage in 1..6) {
            compose.onNodeWithTag("time_screen").performScrollToKey("stage_$stage")
            compose.onNodeWithTag("time_stage_$stage").assertExists()
        }
        for (level in 1..15) {
            compose.onNodeWithTag("time_screen").performScrollToKey("level_$level")
            compose.onNodeWithTag("time_level_$level").assertExists()
                .assertTextContains("Уровень $level · достопримечательность")
                .assertTextContains("До всех M звёзд (M = 2): Время отсутствует")
            val textNodes = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                    hasAnyAncestor(hasTestTag("time_level_$level")), useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("Every field must expose readable text", textNodes.size >= 5)
            textNodes.forEach { node ->
                val action = requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
                val layouts = mutableListOf<TextLayoutResult>()
                action(layouts)
                layouts.forEach { assertFalse("Text must wrap without clipping", it.hasVisualOverflow) }
            }
        }
    }

    @Test fun loadingReadErrorAndUnavailableCampaignDoNotPretendToBeZero() {
        val state = mutableStateOf(TimeUiState())
        compose.setContent { WordsTheme { TimeScreen(state.value) } }
        compose.onNodeWithText("Загрузка времени игры…").assertExists()
        compose.runOnIdle { state.value = TimeUiState(loading = false, error = true) }
        compose.onNodeWithText("Не удалось прочитать время игры.").assertExists()
        compose.onNodeWithTag("time_total").assertDoesNotExist()
        compose.runOnIdle { state.value = TimeUiState(loading = false) }
        compose.onNodeWithText("Время игры доступно для кампании.").assertExists()
    }
}
