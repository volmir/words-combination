package com.example.sostavslova.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Rule
import org.junit.Test

class RulesScreenTest {
    @get:Rule val compose = createComposeRule()

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
