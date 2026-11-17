package com.example.sostavslova.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sostavslova.R
import com.example.sostavslova.data.Definition
import com.example.sostavslova.core.LengthStat
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.ui.theme.WordsTheme
import com.example.sostavslova.ui.screens.AboutScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun drawerKeepsSupportedDestinationsAndInvokesNavigation() {
        var selected: Destination? = null
        compose.setContent { WordsTheme { WordsDrawer(Destination.Levels.route) { selected = it } } }
        compose.onNodeWithText("Каталог компонентов").assertDoesNotExist()
        compose.onNodeWithText("Сервисы").assertDoesNotExist()
        compose.onNodeWithText("Прохождение").assertIsDisplayed()
        compose.onNodeWithText("Правила").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Destination.Rules, selected) }
        compose.onNode(hasText("Об игре") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Destination.About, selected) }
        compose.onNodeWithText("Время игры").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Destination.Time, selected) }
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        compose.onNodeWithText("Поиск слов").assertDoesNotExist()
        compose.onNodeWithText("Словарь").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf("levels", "level/{index}", "time", "rules", "about"), Destination.entries.map { it.route }.toSet())
        }
    }

    @Test
    fun inputLimitsLengthAndSubmitsByImeAndButtonWithoutLosingFocus() {
        var submissions = 0
        compose.setContent {
            var value by remember { mutableStateOf("") }
            WordsTheme {
                WordInputRow(
                    stringResource(R.string.your_answer), value, { value = it },
                    stringResource(R.string.check_answer), { submissions++ },
                )
            }
        }
        val input = compose.onNode(hasSetTextAction())
        input.performTextInput("а".repeat(31))
        input.assertTextEquals("а".repeat(30))
        input.performImeAction()
        input.assertIsFocused()
        compose.runOnIdle { assertEquals(1, submissions) }
        compose.onNodeWithText("Проверить").performClick()
        compose.runOnIdle { assertEquals(2, submissions) }
    }

    @Test
    fun definitionKeepsAngleBracketsNewlinesAndCanHideWordLabel() {
        val text = "<органы управления>\nВторая строка"
        compose.setContent { WordsTheme { DefinitionBlock(Definition("портал", text), labelWord = null) } }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText("портал", substring = true).assertDoesNotExist()
    }

    @Test
    fun statisticsShowsTotalsAndPercentFromCore() {
        compose.setContent { WordsTheme { StatsTable(Statistics(listOf(LengthStat(3, 2, 10), LengthStat(4, 1, 12), LengthStat(5, 0, 6)))) } }
        compose.onNodeWithText("3 / 28").assertIsDisplayed()
        compose.onNodeWithText("11").assertIsDisplayed()
        compose.onNodeWithText("Процент, %").assertIsDisplayed()
    }

    @Test
    fun sourceWordIsAnnouncedAsOnePhraseInsteadOfSeparateLetters() {
        compose.setContent { WordsTheme { LetterTiles("портал") } }
        compose.onNodeWithContentDescription("Исходное слово: портал").assertExists()
    }

    @Test
    fun foundWordsAreDisplayedWithoutClickActions() {
        compose.setContent { WordsTheme { FoundWords(listOf("пар", "порт")) } }
        compose.onNodeWithText("пар").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("порт").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun definitionShowsOriginalArticleUrl() {
        val source = "https://ru.wiktionary.org/wiki/портал"
        compose.setContent { WordsTheme { DefinitionBlock(Definition("портал", "Тест", source = source)) } }
        compose.onNodeWithText("Источник: $source").assertIsDisplayed()
    }

    @Test
    fun hintDoesNotLeakAnswerThroughSourceUrl() {
        val source = "https://ru.wiktionary.org/wiki/портал"
        compose.setContent {
            WordsTheme { DefinitionBlock(Definition("портал", "Тест", source = source), labelWord = null) }
        }
        compose.onNodeWithText("Тест").assertIsDisplayed()
        compose.onNodeWithText(source, substring = true).assertDoesNotExist()
        compose.onNodeWithText("портал", substring = true).assertDoesNotExist()
    }

    @Test
    fun aboutShowsAttributionLicenseAndChangesAtNativeFont() {
        compose.setContent {
            WordsTheme { AboutScreen() }
        }
        compose.onNodeWithText("Авторы — участники русского Викисловаря", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kaikki.org / Wiktextract", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CC BY-SA 4.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Литературные примеры исключены", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Программный код").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("лицензии MIT", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("github.com", substring = true).assertDoesNotExist()
    }
}
