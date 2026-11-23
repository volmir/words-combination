package com.example.sostavslova.ui.theme

import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.alertColors
import com.example.sostavslova.ui.components.starTint
import com.example.sostavslova.ui.components.thresholdMarkerColor
import com.example.sostavslova.ui.game.hintTaskOptionColors
import com.example.sostavslova.ui.levels.levelCardBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Проверяет, что компоненты берут цвета из активной палитры, а не из светлой статики. */
class PaletteApplicationTest {
    @Test
    fun alertColors_followActivePaletteForEverySemanticType() {
        for ((theme, palette) in palettes()) {
            assertEquals("$theme success", Triple(palette.alertSuccessBackground, palette.alertSuccessText, palette.alertSuccessBorder),
                alertColors(palette, AlertType.Success))
            assertEquals("$theme info", Triple(palette.alertInfoBackground, palette.alertInfoText, palette.alertInfoBorder),
                alertColors(palette, AlertType.Info))
            assertEquals("$theme warning", Triple(palette.alertWarningBackground, palette.alertWarningText, palette.alertWarningBorder),
                alertColors(palette, AlertType.Warning))
            assertEquals("$theme danger", Triple(palette.alertDangerBackground, palette.alertDangerText, palette.alertDangerBorder),
                alertColors(palette, AlertType.Danger))
            assertNotEquals("$theme success and danger share a background",
                alertColors(palette, AlertType.Success).first, alertColors(palette, AlertType.Danger).first)
        }
    }

    @Test
    fun levelCardBackground_distinguishesAllFourStatesInBothThemes() {
        for ((theme, palette) in palettes()) {
            assertEquals("$theme locked", palette.levelLockedBackground,
                levelCardBackground(palette, unlocked = false, stars = 3, maxStars = 3, requiredStars = 2))
            assertEquals("$theme allStars", palette.levelAllStarsBackground,
                levelCardBackground(palette, unlocked = true, stars = 3, maxStars = 3, requiredStars = 2))
            assertEquals("$theme passed", palette.levelPassedBackground,
                levelCardBackground(palette, unlocked = true, stars = 2, maxStars = 3, requiredStars = 2))
            assertEquals("$theme available", palette.levelAvailableBackground,
                levelCardBackground(palette, unlocked = true, stars = 1, maxStars = 3, requiredStars = 2))
        }
    }

    @Test
    fun hintTaskOptionColors_distinguishSelectedDisabledAndNormalStatesInBothThemes() {
        for ((theme, palette) in palettes()) {
            val selected = hintTaskOptionColors(palette, selected = true, enabled = true)
            assertEquals("$theme selected container", palette.primary, selected.first)
            assertEquals("$theme selected content", palette.background, selected.second)
            assertEquals("$theme selected border", palette.primary, selected.third)

            val disabled = hintTaskOptionColors(palette, selected = false, enabled = false)
            assertEquals("$theme disabled container", palette.levelLockedBackground, disabled.first)
            assertEquals("$theme disabled content", palette.defaultButtonText, disabled.second)
            assertEquals("$theme disabled border", palette.wellBorder, disabled.third)

            val normal = hintTaskOptionColors(palette, selected = false, enabled = true)
            assertEquals("$theme normal container", palette.background, normal.first)
            assertEquals("$theme normal content", palette.text, normal.second)
            assertEquals("$theme normal border", palette.defaultButtonBorder, normal.third)
        }
    }

    @Test
    fun starTintAndThreshold_usePaletteTokensInBothThemes() {
        for ((theme, palette) in palettes()) {
            assertEquals("$theme done star", palette.starDone, starTint(palette, done = true))
            assertEquals("$theme empty star", palette.starEmpty, starTint(palette, done = false))
            assertEquals("$theme threshold marker", palette.starEmpty.copy(alpha = 0.6f), thresholdMarkerColor(palette))
            assertNotEquals("$theme done and empty stars differ", starTint(palette, true), starTint(palette, false))
        }
    }

    private fun palettes() = listOf("light" to LightWordsPalette, "dark" to DarkWordsPalette)
}
