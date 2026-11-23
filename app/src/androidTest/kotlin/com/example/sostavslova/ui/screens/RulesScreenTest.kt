package com.example.sostavslova.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.AchievementRules
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.ui.theme.WordsTheme
import com.example.sostavslova.ui.assertTextLayoutFits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class RulesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun achievementsExplainPurposeConditionsAndAvailabilityAtLargeFontOnNarrowScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(320.dp)) { RulesScreen(loadOptionalTaskLevel = { null }) }
                }
            }
        }
        compose.onNodeWithTag("rules_achievements").assertExists()
        compose.onNodeWithText(context.getString(R.string.rules_achievements_title))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rules_achievements_intro")
            .assertTextEquals(context.getString(R.string.rules_achievements_intro))
        val expected = listOf(
            Triple(LevelAchievement.NO_HINTS, R.string.achievement_no_hints, context.getString(R.string.achievement_no_hints_condition)),
            Triple(LevelAchievement.EXPERT, R.string.achievement_expert, context.getString(R.string.achievement_expert_condition)),
            Triple(LevelAchievement.COLLECTOR, R.string.achievement_collector, context.getString(R.string.achievement_collector_condition)),
            Triple(LevelAchievement.EXPLORER, R.string.achievement_explorer, context.getString(R.string.achievement_explorer_condition)),
            Triple(LevelAchievement.LONG_WORD, R.string.achievement_long_word,
                context.getString(R.string.achievement_long_word_condition, AchievementRules.LONG_WORD_LENGTH)),
            Triple(LevelAchievement.LONG_WORD_MASTER, R.string.achievement_long_word_master,
                context.getString(R.string.achievement_long_word_master_condition,
                    AchievementRules.LONG_WORD_MASTER_COUNT, AchievementRules.LONG_WORD_LENGTH)),
            Triple(LevelAchievement.GIANT, R.string.achievement_giant,
                context.getString(R.string.achievement_giant_condition, AchievementRules.GIANT_LENGTH)),
            Triple(LevelAchievement.STRAIGHT_A, R.string.achievement_straight_a, context.getString(R.string.achievement_straight_a_condition)),
        )
        // AchievementStrip uses the same fixed enum order; keep the product order explicit here.
        assertEquals(expected.map { it.first }, LevelAchievement.entries)
        var previousBottom = compose.onNodeWithTag("rules_item_8").getUnclippedBoundsInRoot().bottom
        val introBounds = compose.onNodeWithTag("rules_achievements_intro").getUnclippedBoundsInRoot()
        assertTrue(introBounds.top >= previousBottom)
        previousBottom = introBounds.bottom
        expected.forEach { (achievement, name, condition) ->
            val tag = "rules_achievement_${achievement.name.lowercase(Locale.ROOT)}"
            val row = compose.onNodeWithTag(tag)
            val bounds = row.getUnclippedBoundsInRoot()
            assertTrue("Порядок достижений: $achievement", bounds.top >= previousBottom)
            previousBottom = bounds.bottom
            compose.onNodeWithTag("${tag}_name").assertTextEquals(context.getString(name))
            compose.onNodeWithTag("${tag}_condition").assertTextEquals(condition)
            compose.onNodeWithTag("${tag}_icon").assertExists().assertHasNoClickAction()
        }
        val note = compose.onNodeWithTag("rules_achievements_note")
        assertTrue(note.getUnclippedBoundsInRoot().top >= previousBottom)
        note.assertTextEquals(context.getString(R.string.rules_achievements_note, AchievementRules.GIANT_LENGTH))
        val legend = compose.onNodeWithText(context.getString(R.string.rules_colors_title))
        assertTrue(legend.getUnclippedBoundsInRoot().top >= note.getUnclippedBoundsInRoot().bottom)
        expected.forEach { (achievement, _, _) ->
            val tag = "rules_achievement_${achievement.name.lowercase(Locale.ROOT)}"
            compose.onNodeWithTag("${tag}_name").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("${tag}_condition").performScrollTo().assertIsDisplayed()
        }
        legend.performScrollTo().assertIsDisplayed()
        val layouts = compose.onAllNodes(
            hasAnyAncestor(hasTestTag("rules_achievements")) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("Заголовок, вступление, восемь названий и условий, примечание", 19, layouts.size)
        layouts.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            val action = requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            assertTrue(action(results))
            assertTrue(results.isNotEmpty())
            results.forEach { assertTextLayoutFits(it, "Правила: 320 dp, fontScale 2.0") }
        }
    }

    @Test fun colorLegendExplainsEveryLevelStateAndRemainsReachable() {
        compose.setContent { WordsTheme { RulesScreen() } }
        compose.onNodeWithText("Цвета уровней").performScrollTo().assertIsDisplayed()
        val descriptions = listOf(
            "rules_level_color_locked" to "Серый — уровень пока закрыт.",
            "rules_level_color_available" to "Белый — уровень доступен, но ещё не пройден.",
            "rules_level_color_passed" to "Зелёный — уровень пройден, можно добрать звёзды.",
            "rules_level_color_all_stars" to "Золотистый — собраны все звёзды уровня.",
        )
        for ((tag, text) in descriptions) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(text)
        }
    }

    @Test fun rulesListContainsEightItems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = CampaignJson.decode(context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val firstOptional = requireNotNull(CampaignRules.firstOptionalTaskLevel(campaign))
        compose.setContent { WordsTheme { RulesScreen() } }
        // Exactly eight rule items are displayed: rules_item_1..rules_item_8, no rules_item_9.
        for (index in 1..8) {
            compose.onNodeWithTag("rules_item_$index").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("rules_item_9").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("с уровня $firstOptional", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rules_item_4").performScrollTo().assertTextEquals(
            "4. За каждое выполненное задание даётся звезда. На первых уровнях нужно выполнить все задания, " +
                "а начиная с уровня $firstOptional часть заданий можно пропустить. Сколько нужно, указано на экране уровня.",
        )
        val item7 = "7. Подсказка открывает по одной букве слова из ещё не выполненного задания. " +
            "На уровне есть запас подсказок (на ранних этапах больше), и ещё одна добавляется за каждые 4 найденных слова. " +
            "Первая буква ответа в задании с описаниями бесплатна."
        compose.onNodeWithTag("rules_item_7").performScrollTo().assertTextEquals(item7)
        val item8 = "8. В игре используются преимущественно существительные в начальной форме: «кошка», а не «кошки» или «кошку». " +
            "Слова, существующие только во множественном числе (например, «ножницы», «часы»), также могут приниматься, " +
            "если они есть в игровом словаре."
        compose.onNodeWithTag("rules_item_8").performScrollTo().assertTextEquals(item8)
        compose.onNodeWithText("Подсказки не отнимают звёзд.").performScrollTo().assertIsDisplayed()
    }

    @Test fun rulesItemFourFallsBackWithoutNumberWhenCampaignUnavailable() {
        compose.setContent { WordsTheme { RulesScreen(loadOptionalTaskLevel = { null }) } }
        compose.onNodeWithTag("rules_item_4").performScrollTo().assertTextEquals(
            "4. За каждое выполненное задание даётся звезда. На первых уровнях нужно выполнить все задания, " +
                "а позже часть заданий можно пропустить. Сколько нужно, указано на экране уровня.",
        )
    }
}
