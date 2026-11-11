package com.example.sostavslova.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sostavslova.R
import com.example.sostavslova.data.Definition
import com.example.sostavslova.ui.catalog.sampleStatistics
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
    fun definitionDialogShowsUnavailableMode() {
        compose.setContent { WordsTheme { DefinitionDialog("портал", emptyList(), {}, definitionsAvailable = false) } }
        compose.onNodeWithText("Определения недоступны в этой версии").assertIsDisplayed()
    }

    @Test
    fun definitionDialogShowsMissingWordMode() {
        compose.setContent { WordsTheme { DefinitionDialog("портал", emptyList(), {}, definitionsAvailable = true) } }
        compose.onNodeWithText("Значение слова не найдено").assertIsDisplayed()
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
        compose.setContent { WordsTheme { StatsTable(sampleStatistics) } }
        compose.onNodeWithText("3 / 28").assertIsDisplayed()
        compose.onNodeWithText("11").assertIsDisplayed()
        compose.onNodeWithText("Процент, %").assertIsDisplayed()
    }

    @Test
    fun wordLinkInvokesCallbackWithOriginalWord() {
        var selected: String? = null
        compose.setContent { WordsTheme { WordLinks(listOf("пар", "порт"), { selected = it }) } }
        compose.onNodeWithText("порт").performClick()
        compose.runOnIdle { assertEquals("порт", selected) }
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
    fun aboutShowsAttributionLicenseAndChangesAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                WordsTheme { AboutScreen() }
            }
        }
        compose.onNodeWithText("Авторы — участники русского Викисловаря", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kaikki.org / Wiktextract", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CC BY-SA 4.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("литературные примеры не включены", substring = true).performScrollTo().assertIsDisplayed()
    }
}
