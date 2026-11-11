package com.example.sostavslova.ui.levels

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampaignMapUiTest {
    @get:Rule val compose = createComposeRule()

    private fun level(number: Int, stars: Int = 0, recommended: Boolean = false) = LevelUiState(
        number, "слово$number", LevelProgress(), unlocked = true, campaignStars = stars,
        maxStars = 5, requiredStars = 2, stage = 1, recommended = recommended)

    @Test fun fivePositionsHaveAccessibleProgress() {
        compose.setContent { WordsTheme { StarsRow(3, 5) } }
        for (index in 0..4) compose.onNodeWithTag("stars_position_$index", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stars_position_5", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Задания").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Звёзды: 3 из 5"))
    }

    @Test fun summaryStageContinueLockedExplanationFinaleAndMaster() {
        val state = mutableStateOf(LevelsUiState(loading = false,
            levels = listOf(level(1, recommended = true), level(2).copy(unlocked = false)),
            stages = listOf(Stage(1, 1, 2)), recommendedLevel = 1,
            summary = CampaignSummary(0, 10, 0, 2, 0)))
        var selected = ""
        compose.setContent { WordsTheme {
            LevelsScreen(state.value, { selected = it }, {}, { state.value = state.value.copy(lockedRequirement = null) })
        } }
        compose.onNodeWithTag("levels_progress").assertTextEquals("Звёзд 0 из 10 · пройдено 0 из 2")
        compose.onNodeWithTag("levels_stage_1").assertTextEquals("Разминка · уровни 1–2")
        compose.onNodeWithTag("levels_continue").performClick()
        compose.runOnIdle { assertEquals("слово1", selected) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово2")
        compose.onNodeWithTag("level_card_2").performClick()
        compose.runOnIdle {
            assertEquals("слово2", selected) // The click reaches the VM even though navigation is locked.
            state.value = state.value.copy(lockedRequirement = 1 to 2)
        }
        compose.onNodeWithText("Для открытия наберите на уровне 1 2 звезды.").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { state.value = state.value.copy(
            levels = listOf(level(1, 2), level(2, recommended = true)), recommendedLevel = 2,
            summary = CampaignSummary(2, 10, 1, 2, 0)) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_continue").performClick()
        compose.runOnIdle { assertEquals("слово2", selected) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово1")
        compose.onNodeWithText("Можно добрать ещё 3 звезды").assertExists()
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle { assertEquals("слово1", selected) }
        compose.runOnIdle { state.value = state.value.copy(finaleReached = true,
            levels = listOf(level(1, 5), level(2, 2, true).copy(crowned = true)), recommendedLevel = 2,
            summary = CampaignSummary(7, 10, 2, 2, 0)) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_finale_banner").assertTextContains("Финал", substring = true)
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово2")
        compose.onNodeWithContentDescription("Корона: финал кампании достигнут").assertExists()
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово1")
        compose.onNodeWithText("Все звёзды собраны", substring = false).assertExists()
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle {
            assertEquals("слово1", selected)
            state.value = state.value.copy(summary = CampaignSummary(10, 10, 2, 2, 0),
                levels = state.value.levels.map { it.copy(campaignStars = 5) })
        }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_finale_banner").assertTextEquals("Мастер — все звёзды собраны!")
    }

    @Test fun initialRecommendationScrollsOnceAndUpdatesDoNotMoveMap() {
        val state = mutableStateOf(LevelsUiState(loading = false,
            levels = (1..12).map { level(it, recommended = it == 10) },
            stages = listOf(Stage(1, 1, 12)), recommendedLevel = 10))
        compose.setContent { WordsTheme { LevelsScreen(state.value, {}, {}) } }
        compose.onNodeWithTag("level_card_10").assertIsDisplayed()
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово1")
        compose.runOnIdle { state.value = state.value.copy(recommendedLevel = 11,
            levels = state.value.levels.map { it.copy(recommended = it.index == 11) }) }
        compose.onNodeWithTag("level_card_1").assertIsDisplayed()
    }
}
