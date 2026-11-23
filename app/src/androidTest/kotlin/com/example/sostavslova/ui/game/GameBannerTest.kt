package com.example.sostavslova.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.sostavslova.core.LevelCompleteSummary
import com.example.sostavslova.R
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class GameBannerTest {
    private val scale = mutableFloatStateOf(1f)
    private val durationScale = object : MotionDurationScale {
        override val scaleFactor: Float get() = scale.floatValue
    }
    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    private fun show(message: GameMessage) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WordsTheme {
                Box(Modifier.background(Color.White).padding(8.dp)) { GameBanner(message) }
            }
        }
        compose.waitForIdle()
    }

    private fun center(tag: String): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test fun rewardFadesInAndFinishesWithin300ms() {
        show(requireNotNull(awardMessage(1, false, emptyList())))
        assertEquals(Color.White, center("game_banner"))
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val finalColor = center("game_banner")
        assertNotEquals(Color.White, finalColor)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(finalColor, center("game_banner"))
    }

    @Test fun errorsAreVisibleImmediately() {
        show(GameMessage(R.string.answer_unknown, word = "кот", type = AlertType.Danger))
        assertNotEquals(Color.White, center("game_banner"))
    }

    @Test fun ordinarySuccessIsVisibleImmediately() {
        show(GameMessage(R.string.correct_answer, type = AlertType.Success))
        assertNotEquals(Color.White, center("game_banner"))
    }

    @Test fun zeroSystemScaleShowsRewardAndAllDialogStarsImmediately() {
        scale.floatValue = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WordsTheme {
                Column(Modifier.background(Color.White).padding(8.dp)) {
                    GameBanner(requireNotNull(awardMessage(1, false, emptyList())))
                    LevelCompleteStars(6, 6)
                }
            }
        }
        compose.waitForIdle()
        assertNotEquals(Color.White, center("game_banner"))
        repeat(6) { assertNotEquals(Color.White, center("level_complete_star_$it")) }
    }

    @Test fun zeroSystemScaleShowsLevelCompleteDialogWithoutAdvancingClock() {
        scale.floatValue = 0f
        compose.mainClock.autoAdvance = false
        val summary = LevelCompleteSummary(1, "вегетарианец", 3, 6,
            emptySet(), emptySet(), emptySet(), 10, 20, 0, true)
        compose.setContent {
            WordsTheme { LevelCompleteDialog(summary, {}, {}) }
        }
        compose.onNodeWithTag("level_complete_dialog").assertIsDisplayed()
        compose.onNodeWithTag("level_complete_stars").assertIsDisplayed()
        repeat(6) { compose.onNodeWithTag("level_complete_star_$it").assertIsDisplayed() }
        compose.onNodeWithTag("level_complete_next").assertIsDisplayed()
    }

    @Test fun starsAppearInOrderAndFinishWithin600ms() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WordsTheme {
                Box(Modifier.background(Color.White).padding(8.dp)) { LevelCompleteStars(6, 6) }
            }
        }
        compose.waitForIdle()
        repeat(6) { assertEquals(Color.White, center("level_complete_star_$it")) }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertNotEquals(Color.White, center("level_complete_star_0"))
        assertEquals(Color.White, center("level_complete_star_1"))
        compose.mainClock.advanceTimeBy(536)
        compose.waitForIdle()
        val finalColors = List(6) { center("level_complete_star_$it") }
        finalColors.forEach { assertNotEquals(Color.White, it) }
        compose.mainClock.advanceTimeBy(300)
        repeat(6) { assertEquals(finalColors[it], center("level_complete_star_$it")) }
    }

    @Test fun disablingAnimationsDuringRevealShowsFinalFrame() {
        show(requireNotNull(awardMessage(1, false, emptyList())))
        compose.runOnUiThread { scale.floatValue = 0f }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertNotEquals(Color.White, center("game_banner"))
    }
}
