package com.example.sostavslova.ui.levels

import com.example.sostavslova.ui.captureToImageCompat
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
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
import com.example.sostavslova.ui.theme.DarkWordsPalette
import com.example.sostavslova.ui.theme.LocalWordsPalette
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampaignMapUiTest {
    @get:Rule val compose = createComposeRule()

    private fun level(number: Int, stars: Int = 0, recommended: Boolean = false) = LevelUiState(
        number, "слово$number", stars, unlocked = true,
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
            val pixels = card.captureToImageCompat().toPixelMap()
            return pixels[(6 * compose.density.density).toInt(), pixels.height / 2]
        }
        val available = background(open)
        val unavailable = background(locked)
        val passed = background(compose.onNodeWithTag("level_card_3"))
        val complete = background(compose.onNodeWithTag("level_card_4"))
        assertTrue("Доступный фон — белый", available.red > 0.95f && available.green > 0.95f && available.blue > 0.95f)
        assertTrue("Закрытый фон — нейтральный серый", unavailable.red < available.red)
        assertEquals(unavailable.red, unavailable.green, 0.01f)
        assertEquals(unavailable.green, unavailable.blue, 0.01f)
        assertTrue("Пройденный фон — лёгкий зелёный", passed.green > passed.red && passed.green > passed.blue)
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
            if (it.index == 2) it.copy(stars = 2) else it
        }) }
        assertBackground(WordsColors.levelPassedBackground, background(locked))
        compose.runOnIdle { state.value = state.value.copy(levels = state.value.levels.map {
            if (it.index == 2) it.copy(stars = 5) else it
        }) }
        assertBackground(WordsColors.levelAllStarsBackground, background(locked))
        locked.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "Получено 5 из 5 звёзд · для открытия нужно: 2"))
    }

    @Test fun achievementCountUsesAvailableIntersectionAndFollowsProgressAndUnlock() {
        val all = LevelAchievement.entries.toSet()
        val basic = AchievementRules.available(GameState("слово", listOf("лов")), 1, 1)
        val state = mutableStateOf(LevelsUiState(loading = false, levels = listOf(
            level(1).copy(achievementsAvailable = all, achievements = all),
            level(2).copy(achievementsAvailable = basic, achievements = setOf(LevelAchievement.EXPERT, LevelAchievement.GIANT)),
            level(3).copy(achievementsAvailable = emptySet(), achievements = all),
            level(4).copy(unlocked = false, achievementsAvailable = all, achievements = all),
        )))
        compose.setContent { WordsTheme { LevelsScreen(state.value, {}, {}) } }
        fun scroll(number: Int) { compose.onNodeWithTag("levels_grid").performScrollToKey("слово$number") }
        fun count(number: Int, expected: String) {
            compose.onNodeWithTag("level_achievements_$number", useUnmergedTree = true).assertTextEquals(expected)
        }
        scroll(1)
        count(1, "Достижения: 8/8")
        compose.onNodeWithTag("level_card_1").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Получено 0 из 5 звёзд · для открытия нужно: 2. Достижения: 8 из 8"))
        scroll(2)
        count(2, "Достижения: 1/4") // Сохранённый GIANT сейчас недоступен и не входит в числитель.
        scroll(3)
        compose.onNodeWithTag("level_achievements_3", useUnmergedTree = true).assertDoesNotExist()
        scroll(4)
        compose.onNodeWithTag("level_achievements_4", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(levels = state.value.levels.map {
            if (it.index == 4) it.copy(unlocked = true, achievements = emptySet()) else it
        }) }
        count(4, "Достижения: 0/8")
        for (achievement in LevelAchievement.entries) {
            compose.onNodeWithTag("achievement_icon_${achievement.name.lowercase(java.util.Locale.ROOT)}").assertDoesNotExist()
        }
    }

    @Test fun darkThemeLevelCardsRenderAdaptedStateColors() {
        val state = mutableStateOf(LevelsUiState(loading = false,
            levels = listOf(level(1), level(2).copy(unlocked = false), level(3, stars = 2), level(4, stars = 5)),
            stages = listOf(Stage(1, 1, 4))))
        compose.setContent {
            WordsTheme {
                CompositionLocalProvider(LocalWordsPalette provides DarkWordsPalette) {
                    Surface(color = DarkWordsPalette.background) { LevelsScreen(state.value, {}, {}) }
                }
            }
        }
        fun background(card: SemanticsNodeInteraction): Color {
            val pixels = card.captureToImageCompat().toPixelMap()
            return pixels[(6 * compose.density.density).toInt(), pixels.height / 2]
        }
        val available = background(compose.onNodeWithTag("level_card_1"))
        val locked = background(compose.onNodeWithTag("level_card_2"))
        val passed = background(compose.onNodeWithTag("level_card_3"))
        val allStars = background(compose.onNodeWithTag("level_card_4"))

        fun assertBackground(expected: Color, actual: Color) {
            val opaque = expected.compositeOver(DarkWordsPalette.background)
            assertEquals(opaque.red, actual.red, 2f / 255)
            assertEquals(opaque.green, actual.green, 2f / 255)
            assertEquals(opaque.blue, actual.blue, 2f / 255)
        }
        assertBackground(DarkWordsPalette.levelAvailableBackground, available)
        assertBackground(DarkWordsPalette.levelLockedBackground, locked)
        assertBackground(DarkWordsPalette.levelPassedBackground, passed)
        assertBackground(DarkWordsPalette.levelAllStarsBackground, allStars)

        val backgrounds = listOf(available, locked, passed, allStars)
        assertEquals(4, backgrounds.map { Triple(it.red, it.green, it.blue) }.toSet().size)
        assertTrue("Закрытый — нейтральный серый",
            maxOf(locked.red, locked.green, locked.blue) - minOf(locked.red, locked.green, locked.blue) < 0.02f)
        assertTrue("Доступный — тёмная поверхность",
            maxOf(available.red, available.green, available.blue) < 0.25f)
        assertTrue("Закрытый приглушённее доступного", locked.red < available.red)
        assertTrue("Пройденный — зелёный", passed.green > passed.red && passed.green > passed.blue)
        assertTrue("Все звёзды — янтарный", allStars.red > allStars.green && allStars.green > allStars.blue)
    }

    @Test fun fivePositionsHaveAccessibleProgress() {
        compose.setContent { WordsTheme { StarsRow(3, 5) } }
        for (index in 0..4) compose.onNodeWithTag("stars_position_$index", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stars_position_5", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("stars_threshold", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Звёзды").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Получено 3 из 5 звёзд"))
        compose.onAllNodesWithContentDescription("Звёзды").assertCountEquals(1)
    }

    @Test fun requiredStarsAreMarkedByDividerAndAnnounced() {
        compose.setContent { WordsTheme { StarsRow(3, 5, requiredStars = 3) } }
        val divider = compose.onNodeWithTag("stars_threshold", useUnmergedTree = true).assertExists()
            .fetchSemanticsNode().boundsInRoot
        val required = compose.onNodeWithTag("stars_position_2", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("stars_position_3", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(divider.left >= required.right)
        assertTrue(divider.right <= next.left)
        compose.onNodeWithContentDescription("Звёзды").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Получено 3 из 5 звёзд · для открытия нужно: 3"))
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
        compose.onNodeWithText("Чтобы открыть этот уровень, на уровне 1 выполните 2 из 5 заданий.").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { state.value = state.value.copy(
            levels = listOf(level(1, 2), level(2, recommended = true)), recommendedLevel = 2,
            summary = CampaignSummary(2, 10, 1, 2, 0)) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_continue").performClick()
        compose.runOnIdle { assertEquals("слово2", selected) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово1")
        compose.onNodeWithTag("level_card_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Получено 2 из 5 звёзд · для открытия нужно: 2"))
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle { assertEquals("слово1", selected) }
        compose.runOnIdle { state.value = state.value.copy(finaleReached = true,
            levels = listOf(level(1, 5), level(2, 2, true).copy(crowned = true)), recommendedLevel = 2,
            summary = CampaignSummary(7, 10, 2, 2, 0)) }
        compose.onNodeWithTag("levels_grid").performScrollToKey("levels.header")
        compose.onNodeWithTag("levels_finale_banner").assertTextContains("Финал", substring = true)
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово2")
        compose.onNodeWithTag("level_card_2")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Получено 2 из 5 звёзд · для открытия нужно: 2"))
            .assertTextEquals("2. СЛОВО2")
        compose.onAllNodesWithText("Рекомендуемый уровень", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Корона: финал кампании достигнут").assertExists()
        compose.onNodeWithTag("levels_grid").performScrollToKey("слово1")
        compose.onNodeWithTag("level_card_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Получено 5 из 5 звёзд · для открытия нужно: 2"))
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle {
            assertEquals("слово1", selected)
            state.value = state.value.copy(summary = CampaignSummary(10, 10, 2, 2, 0),
                levels = state.value.levels.map { it.copy(stars = 5) })
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
            "Добро пожаловать в «Составь слова»! Составляйте слова из букв исходного слова, " +
                "выполняйте задания, получайте звёзды и открывайте новые уровни. " +
                "Выберите первый доступный уровень — прогресс сохраняется автоматически.",
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
