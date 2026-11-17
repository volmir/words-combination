package com.example.sostavslova.ui.levels

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.theme.WordsTheme
import com.example.sostavslova.ui.theme.WordsColors
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

    @Test fun compactCardsUseSubtleAvailabilityColorsAndUpdateWhenUnlocked() {
        val state = mutableStateOf(LevelsUiState(loading = false,
            levels = listOf(level(1), level(2).copy(unlocked = false), level(3, stars = 2), level(4, stars = 5)),
            stages = listOf(Stage(1, 1, 4))))
        compose.setContent { WordsTheme {
            Surface(color = WordsColors.background) { LevelsScreen(state.value, {}, {}) }
        } }
        val open = compose.onNodeWithTag("level_card_1")
        val locked = compose.onNodeWithTag("level_card_2")
        compose.onAllNodesWithText("Закрыт").assertCountEquals(0)
        compose.onAllNodesWithText("Открыт").assertCountEquals(0)
        compose.onAllNodesWithText("Пройден").assertCountEquals(0)
        compose.onAllNodesWithText("Все звёзды").assertCountEquals(0)
        locked.assert(hasContentDescription("Уровень недоступен"))
        open.assert(hasContentDescription("Уровень недоступен").not())
        for (number in 1..4) compose.onNodeWithTag("level_card_$number").assertTextEquals("$number. СЛОВО$number")
        // Замочек может увеличить высоту заголовка, но отметки прохождения
        // больше не добавляют отдельную строку.
        for (number in 3..4) assertEquals(open.fetchSemanticsNode().size.height,
            compose.onNodeWithTag("level_card_$number").fetchSemanticsNode().size.height)

        fun background(card: SemanticsNodeInteraction): Color {
            val pixels = card.captureToImage().toPixelMap()
            return pixels[(6 * compose.density.density).toInt(), pixels.height / 2]
        }
        val available = background(open)
        val unavailable = background(locked)
        val passed = background(compose.onNodeWithTag("level_card_3"))
        val complete = background(compose.onNodeWithTag("level_card_4"))
        assertTrue("Доступный фон — лёгкий зелёный", available.green > available.red)
        assertTrue("Закрытый фон — лёгкий розовый", unavailable.red > unavailable.green)
        assertTrue("Пройденный фон — лёгкий голубой", passed.blue > passed.red)
        assertTrue("Все звёзды — лёгкий золотистый", complete.red > complete.green && complete.green > complete.blue)
        val backgrounds = listOf(available, unavailable, passed, complete)
        assertEquals(4, backgrounds.map { Triple(it.red, it.green, it.blue) }.toSet().size)
        for (color in backgrounds) {
            assertTrue("Фон должен оставаться светлым для контраста звёзд и текста", minOf(color.red, color.green, color.blue) > 0.78f)
        }
        fun assertBackground(expected: Color, actual: Color) {
            val opaque = expected.compositeOver(WordsColors.background)
            assertEquals(opaque.red, actual.red, 2f / 255)
            assertEquals(opaque.green, actual.green, 2f / 255)
            assertEquals(opaque.blue, actual.blue, 2f / 255)
        }
        assertBackground(WordsColors.levelAvailableBackground, available)
        assertBackground(WordsColors.levelLockedBackground, unavailable)
        assertBackground(WordsColors.levelPassedBackground, passed)
        assertBackground(WordsColors.levelAllStarsBackground, complete)

        compose.runOnIdle { state.value = state.value.copy(levels = state.value.levels.map {
            it.copy(unlocked = true)
        }) }
        locked.assert(hasContentDescription("Уровень недоступен").not())
        compose.onAllNodesWithText("Открыт").assertCountEquals(0)
        assertEquals(open.fetchSemanticsNode().size.height, locked.fetchSemanticsNode().size.height)
        assertBackground(WordsColors.levelAvailableBackground, background(locked))
        compose.runOnIdle { state.value = state.value.copy(levels = state.value.levels.map {
            if (it.index == 2) it.copy(campaignStars = 2) else it
        }) }
        assertBackground(WordsColors.levelPassedBackground, background(locked))
        compose.runOnIdle { state.value = state.value.copy(levels = state.value.levels.map {
            if (it.index == 2) it.copy(campaignStars = 5) else it
        }) }
        assertBackground(WordsColors.levelAllStarsBackground, background(locked))
        locked.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Получено 5 из 5 звёзд"))
    }

    @Test fun fivePositionsHaveAccessibleProgress() {
        compose.setContent { WordsTheme { StarsRow(3, 5) } }
        for (index in 0..4) compose.onNodeWithTag("stars_position_$index", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stars_position_5", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Задания").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Получено 3 из 5 звёзд"))
        compose.onAllNodesWithContentDescription("Задания").assertCountEquals(1)
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
        compose.onNodeWithTag("level_card_1")
            .assert(hasContentDescription("Уровень недоступен").not())
            .assertTextEquals("1. СЛОВО1")
        compose.onAllNodesWithText("Рекомендуемый уровень", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("level_card_2").assert(hasContentDescription("Уровень недоступен"))
        compose.onAllNodesWithText("Для прохождения", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Можно добрать", substring = true).assertCountEquals(0)
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
        compose.onNodeWithTag("level_card_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Получено 2 из 5 звёзд"))
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle { assertEquals("слово1", selected) }
        compose.runOnIdle { state.value = state.value.copy(finaleReached = true,
            levels = listOf(level(1, 5), level(2, 2, true).copy(crowned = true)), recommendedLevel = 2,
            summary = CampaignSummary(7, 10, 2, 2, 0)) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_finale_banner").assertTextContains("Финал", substring = true)
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово2")
        compose.onNodeWithTag("level_card_2")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Получено 2 из 5 звёзд"))
            .assertTextEquals("2. СЛОВО2")
        compose.onAllNodesWithText("Рекомендуемый уровень", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Корона: финал кампании достигнут").assertExists()
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово1")
        compose.onNodeWithTag("level_card_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Получено 5 из 5 звёзд"))
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle {
            assertEquals("слово1", selected)
            state.value = state.value.copy(summary = CampaignSummary(10, 10, 2, 2, 0),
                levels = state.value.levels.map { it.copy(campaignStars = 5) })
        }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_finale_banner").assertTextEquals("Мастер — все звёзды собраны!")
    }

    @Test fun welcomeWaitsForReadyMapAndIsPresentedOncePerState() {
        val state = mutableStateOf(LevelsUiState(loading = true, showWelcome = true))
        var presented = 0
        compose.setContent { WordsTheme {
            LevelsScreen(state.value, {}, {}, onWelcomeShown = { presented++ })
        } }
        compose.waitForIdle()
        assertEquals(0, presented)
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(loading = false, loadError = true) }
        compose.waitForIdle()
        assertEquals(0, presented)
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(loadError = false, levels = emptyList()) }
        compose.waitForIdle()
        assertEquals(0, presented)
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(levels = listOf(level(1))) }
        compose.waitForIdle()
        assertEquals(1, presented)
        compose.onNodeWithTag("levels_welcome").assertIsDisplayed()
        compose.onNodeWithTag("levels_welcome_text").assertTextEquals(
            "Добро пожаловать в „Составь слова“! Составляйте слова из букв исходного слова, " +
                "выполняйте задания, получайте звёзды и открывайте новые уровни. " +
                "Выберите первый доступный уровень — прогресс сохраняется автоматически",
        )
        compose.runOnIdle { state.value = state.value.copy(recommendedLevel = 2) }
        compose.waitForIdle()
        assertEquals(1, presented)
        compose.onNodeWithTag("levels_welcome_confirm").assertTextEquals("Понятно")
    }

    @Test fun loadingShowsNeutralSpaceWithoutFakeCardsThenErrorRetryAndSuccess() {
        val state = mutableStateOf(LevelsUiState(loading = true, showWelcome = true))
        var retries = 0
        compose.setContent { WordsTheme {
            LevelsScreen(state.value, {}, { retries++ }, onWelcomeShown = {})
        } }
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onAllNodesWithText("Загружаем уровни…").assertCountEquals(0)
        compose.onAllNodesWithTag("level_card_1").assertCountEquals(0)
        compose.onAllNodesWithTag("levels_grid").assertCountEquals(0)
        compose.onAllNodesWithTag("levels_welcome").assertCountEquals(0)

        compose.runOnIdle { state.value = state.value.copy(loading = false, loadError = true) }
        compose.onNodeWithText("Не удалось загрузить уровни. Попробуйте ещё раз.").assertIsDisplayed()
        compose.onNodeWithTag("levels_retry").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onAllNodesWithTag("level_card_1").assertCountEquals(0)

        compose.runOnIdle { state.value = state.value.copy(loading = true, loadError = false) }
        compose.onAllNodesWithTag("level_card_1").assertCountEquals(0)

        compose.runOnIdle {
            state.value = state.value.copy(loading = false, levels = listOf(level(1, recommended = true)),
                stages = listOf(Stage(1, 1, 1)), recommendedLevel = 1)
        }
        compose.onNodeWithTag("level_card_1").assertIsDisplayed()
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
