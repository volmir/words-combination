package com.example.sostavslova.ui.time

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.assertTextLayoutFits
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
    private val stageNames = listOf("Разминка", "Первая буква", "Длинные слова", "Загадки", "Окончания", "Мастер")

    private fun ready(ms: Long? = null, stars: Int = 0, timeToRMs: Long? = ms): TimeUiState =
        TimeUiState(loading = false, campaign = campaign,
            summary = CampaignTimeStatistics.aggregate(campaign, CampaignTimeSnapshot(
                requireNotNull(campaign.contentSha256),
                if (ms == null) emptyMap() else mapOf(1 to LevelTimeMeasurement(
                    "достопримечательность", ms, true, timeToRMs, null)))),
            levelStars = campaign.levels.associate { it.number to stars })

    @Test fun localSnapshotChangesAppearWithoutRefreshAndMissingValuesAreExplicit() {
        val state = mutableStateOf(ready(stars = 1).copy(historicalLevels = setOf(2)))
        compose.setContent { WordsTheme { TimeScreen(state.value) } }
        compose.onNodeWithTag("time_total").assertTextEquals("Общее активное время: 0 с")
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1")
            .assertTextContains("Активное время: —")
            .assertTextContains("Пройден за: время не измерено")
            .assertTextContains("Все звёзды за: ещё не пройден")
        compose.onNodeWithTag("time_screen").performScrollToKey("level_2")
        compose.onNodeWithTag("time_level_2").assertTextContains("Активное время: Уже играл до включения измерения: время неизвестно")
        compose.runOnIdle { state.value = ready(97_385_000, stars = 2) }
        compose.onNodeWithTag("time_screen").performScrollToIndex(1)
        compose.onNodeWithTag("time_total").assertTextEquals("Общее активное время: 27 ч 3 мин 5 с")
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1")
            .assertTextContains("Активное время: 27 ч 3 мин 5 с")
            .assertTextContains("Пройден за: 27 ч 3 мин 5 с")
            .assertTextContains("Все звёзды за: время не измерено")
            .assertHasNoClickAction()
    }

    @Test fun measuredTimeDoesNotRepeatHistoricalNoteAndStagesUseMapNames() {
        compose.setContent {
            WordsTheme { TimeScreen(ready(5_000, stars = 1, timeToRMs = null)
                .copy(historicalLevels = setOf(1))) }
        }
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1")
            .assertTextContains("Активное время: 5 с")
            .assertTextContains("Пройден за: время не измерено")
        compose.onNodeWithText("Уже играл до включения измерения", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("time_screen").performScrollToKey("stage_1")
        compose.onNodeWithTag("time_stage_1").assertTextEquals("Разминка: 5 с")
        compose.onNodeWithTag("time_screen").performScrollToKey("stage_6")
        compose.onNodeWithTag("time_stage_6").assertTextEquals("Мастер: 0 с")
    }

    @Test fun thresholdLabelsHideInternalNotationAndAggregateStatistics() {
        compose.setContent { WordsTheme { TimeScreen(ready(stars = 1)) } }
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1")
            .assertTextContains("Пройден за: время не измерено", substring = true)
            .assertTextContains("Все звёзды за: ещё не пройден", substring = true)
        compose.onAllNodesWithText("R", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("M", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Среднее", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Медиана", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Самый быстрый", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Самый долгий", substring = true).assertDoesNotExist()
    }

    @Test fun allStarsLineIsHiddenWhenRequiredThresholdIsTheMaximum() {
        val singleTaskCampaign = Campaign(listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "достопримечательность", 1, 1,
                listOf(CampaignTask.T1("r", 1)))), contentSha256 = "a".repeat(64))
        val state = TimeUiState(loading = false, campaign = singleTaskCampaign,
            summary = CampaignTimeStatistics.aggregate(singleTaskCampaign,
                CampaignTimeSnapshot(requireNotNull(singleTaskCampaign.contentSha256), emptyMap())),
            levelStars = mapOf(1 to 1))
        compose.setContent { WordsTheme { TimeScreen(state) } }
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1")
            .assertTextContains("Пройден за: время не измерено")
        compose.onAllNodesWithText("Все звёзды за", substring = true).assertCountEquals(0)
    }

    @Test fun allFieldsFitNativeScreenAndKeepTalkBackHeadings() {
        compose.setContent {
            WordsTheme { TimeScreen(ready(Long.MAX_VALUE, stars = 2)) }
        }
        compose.onNodeWithText("Время игры").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        for (stage in 1..6) {
            compose.onNodeWithTag("time_screen").performScrollToKey("stage_$stage")
            compose.onNodeWithTag("time_stage_$stage").assertExists()
                .assertTextContains(stageNames[stage - 1], substring = true)
        }
        for (level in 1..15) {
            compose.onNodeWithTag("time_screen").performScrollToKey("level_$level")
            compose.onNodeWithTag("time_level_$level").assertExists()
                .assertTextContains("Уровень $level · достопримечательность")
                .assertTextContains("Все звёзды за: время не измерено")
            assertLevelFieldsFit(level)
        }
    }

    @Test fun longLevelNameRemainsFullyReadable() {
        compose.setContent { WordsTheme { TimeScreen(ready()) } }
        compose.onNodeWithTag("time_screen").performScrollToKey("level_15")
        compose.onNodeWithTag("time_level_15")
            .assertTextContains("Уровень 15 · достопримечательность")
            .assertHasNoClickAction()
        assertLevelFieldsFit(15)
    }

    @Test fun veryLongDurationRemainsFullyReadableInSummaryAndLevel() {
        compose.setContent { WordsTheme { TimeScreen(ready(Long.MAX_VALUE, stars = 2)) } }
        val duration = "2562047788015 ч 12 мин 55 с"
        compose.onNodeWithTag("time_screen").performScrollToIndex(1)
        val total = compose.onNodeWithTag("time_total")
            .assertTextEquals("Общее активное время: $duration")
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = requireNotNull(total.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
        assertTrue("Summary layout must be available", getLayout(layouts))
        assertTrue("Summary must expose readable text", layouts.isNotEmpty())
        layouts.forEach { assertTextLayoutFits(it, "total duration") }
        compose.onNodeWithTag("time_screen").performScrollToKey("level_1")
        compose.onNodeWithTag("time_level_1")
            .assertTextContains("Активное время: $duration")
            .assertTextContains("Пройден за: $duration")
            .assertTextContains("Все звёзды за: время не измерено")
        assertLevelFieldsFit(1)
    }

    private fun assertLevelFieldsFit(level: Int) {
        val textNodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                hasAnyAncestor(hasTestTag("time_level_$level")), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("Every field must expose readable text", textNodes.size >= 5)
        textNodes.forEach { node ->
            val action = requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue("Text layout must be available", action(layouts))
            assertTrue("Text layout must not be empty", layouts.isNotEmpty())
            layouts.forEach { layout ->
                val details = "level=$level text=${layout.layoutInput.text} " +
                    "constraints=${layout.layoutInput.constraints} size=${layout.size} " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                    "lines=${layout.lineCount} widthOverflow=${layout.didOverflowWidth} " +
                    "heightOverflow=${layout.didOverflowHeight} " +
                    "lineBounds=" + (0 until layout.lineCount).map { line ->
                        listOf(layout.getLineLeft(line), layout.getLineTop(line),
                            layout.getLineRight(line), layout.getLineBottom(line))
                    }
                Log.i("G15Layout", details)
                assertTextLayoutFits(layout, details)
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
