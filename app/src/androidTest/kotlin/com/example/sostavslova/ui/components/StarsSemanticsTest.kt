package com.example.sostavslova.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Одна группа звёзд вместо декоративных иконок; значения берутся из аргументов модели. */
@RunWith(AndroidJUnit4::class)
class StarsSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun groupAnnouncesModelProgressAsOneReadOnlyNodeForEveryShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = listOf(0 to 1, 1 to 2, 3 to 4, 4 to 5, 5 to 5)
        val current = mutableStateOf(cases.first())
        compose.setContent {
            WordsTheme {
                val (stars, maxStars) = current.value
                StarsRow(stars, maxStars)
            }
        }
        for ((stars, maxStars) in cases) {
            compose.runOnIdle { current.value = stars to maxStars }
            val expected = context.resources.getQuantityString(
                R.plurals.level_stars_progress, maxStars, stars, maxStars,
            )
            compose.onNodeWithContentDescription("Задания")
                .assertExists()
                .assertHasNoClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
            compose.onAllNodesWithContentDescription("Задания").assertCountEquals(1)
            for (index in 0 until maxStars) {
                compose.onNodeWithTag("stars_position_$index", useUnmergedTree = true)
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            }
            compose.onAllNodesWithTag("stars_position_$maxStars", useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun interactiveGroupKeepsTaskAction() {
        var clicks = 0
        compose.setContent {
            WordsTheme { StarsRow(LevelProgress(completed = true, wordsTask = true), onClick = { clicks++ }) }
        }
        compose.onNodeWithContentDescription("Задания")
            .assertExists()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Получено 2 из 3 звёзд"))
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
