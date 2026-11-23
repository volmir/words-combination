package com.example.sostavslova.ui.theme

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.sostavslova.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.pow

/**
 * Выбор палитры проверяется здесь по чистым функциям и через CompositionLocal.
 * Фактическое переключение системного night mode (Settings → Display → Dark theme)
 * требует устройства или эмулятора и остаётся ручной проверкой: Activity и Compose
 * обновляются системой, а настройки темы в приложении нет.
 */
class WordsThemePaletteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mainActivity_handlesUiModeWithoutRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(
            "MainActivity must handle uiMode changes without recreating the Activity",
            activityInfo.configChanges and ActivityInfo.CONFIG_UI_MODE != 0,
        )
    }

    @Test
    fun lightPalette_keepsEveryOriginalColorValue() {
        assertEquals(WordsColors.navbar, LightWordsPalette.navbar)
        assertEquals(WordsColors.navbarText, LightWordsPalette.navbarText)
        assertEquals(WordsColors.navbarActive, LightWordsPalette.navbarActive)
        assertEquals(WordsColors.primary, LightWordsPalette.primary)
        assertEquals(WordsColors.primaryPressed, LightWordsPalette.primaryPressed)
        assertEquals(WordsColors.defaultButton, LightWordsPalette.defaultButton)
        assertEquals(WordsColors.defaultButtonBorder, LightWordsPalette.defaultButtonBorder)
        assertEquals(WordsColors.defaultButtonText, LightWordsPalette.defaultButtonText)
        assertEquals(WordsColors.slotEmptyBorder, LightWordsPalette.slotEmptyBorder)
        assertEquals(WordsColors.slotOpenText, LightWordsPalette.slotOpenText)
        assertEquals(WordsColors.dangerButton, LightWordsPalette.dangerButton)
        assertEquals(WordsColors.successButton, LightWordsPalette.successButton)
        assertEquals(WordsColors.levelAvailableBackground, LightWordsPalette.levelAvailableBackground)
        assertEquals(WordsColors.levelLockedBackground, LightWordsPalette.levelLockedBackground)
        assertEquals(WordsColors.levelPassedBackground, LightWordsPalette.levelPassedBackground)
        assertEquals(WordsColors.wellBackground, LightWordsPalette.wellBackground)
        assertEquals(WordsColors.wellBorder, LightWordsPalette.wellBorder)
        assertEquals(WordsColors.inputFocusBorder, LightWordsPalette.inputFocusBorder)
        assertEquals(WordsColors.tileBorder, LightWordsPalette.tileBorder)
        assertEquals(WordsColors.starDone, LightWordsPalette.starDone)
        assertEquals(WordsColors.levelAllStarsBackground, LightWordsPalette.levelAllStarsBackground)
        assertEquals(WordsColors.starEmpty, LightWordsPalette.starEmpty)
        assertEquals(WordsColors.tableHead, LightWordsPalette.tableHead)
        assertEquals(WordsColors.tableFoot, LightWordsPalette.tableFoot)
        assertEquals(WordsColors.alertSuccessBackground, LightWordsPalette.alertSuccessBackground)
        assertEquals(WordsColors.alertSuccessText, LightWordsPalette.alertSuccessText)
        assertEquals(WordsColors.alertSuccessBorder, LightWordsPalette.alertSuccessBorder)
        assertEquals(WordsColors.alertInfoBackground, LightWordsPalette.alertInfoBackground)
        assertEquals(WordsColors.alertInfoText, LightWordsPalette.alertInfoText)
        assertEquals(WordsColors.alertInfoBorder, LightWordsPalette.alertInfoBorder)
        assertEquals(WordsColors.alertWarningBackground, LightWordsPalette.alertWarningBackground)
        assertEquals(WordsColors.alertWarningText, LightWordsPalette.alertWarningText)
        assertEquals(WordsColors.alertWarningBorder, LightWordsPalette.alertWarningBorder)
        assertEquals(WordsColors.alertDangerBackground, LightWordsPalette.alertDangerBackground)
        assertEquals(WordsColors.alertDangerText, LightWordsPalette.alertDangerText)
        assertEquals(WordsColors.alertDangerBorder, LightWordsPalette.alertDangerBorder)
        assertEquals(WordsColors.labelBackground, LightWordsPalette.labelBackground)
        assertEquals(WordsColors.labelText, LightWordsPalette.labelText)
        assertEquals(WordsColors.blockquoteBorder, LightWordsPalette.blockquoteBorder)
        assertEquals(WordsColors.text, LightWordsPalette.text)
        assertEquals(WordsColors.background, LightWordsPalette.background)
    }

    @Test
    fun paletteSelection_dependsOnlyOnNightMode() {
        assertEquals(LightWordsPalette, wordsPalette(false))
        assertEquals(DarkWordsPalette, wordsPalette(true))
        assertNotEquals(LightWordsPalette.background, DarkWordsPalette.background)
        assertNotEquals(LightWordsPalette.text, DarkWordsPalette.text)
        assertNotEquals(LightWordsPalette.primary, DarkWordsPalette.primary)
        assertNotEquals(LightWordsPalette.wellBackground, DarkWordsPalette.wellBackground)
        assertNotEquals(LightWordsPalette.levelLockedBackground, DarkWordsPalette.levelLockedBackground)
        assertNotEquals(LightWordsPalette.alertDangerText, DarkWordsPalette.alertDangerText)
    }

    @Test
    fun colorSchemes_areConsistentWithBothPalettes() {
        assertSchemeMatches(wordsColorScheme(false), LightWordsPalette)
        assertSchemeMatches(wordsColorScheme(true), DarkWordsPalette)
    }

    @Test
    fun wordsTheme_providesActivePaletteAndSchemeToContent() {
        lateinit var palette: WordsPalette
        var darkTheme = false
        var primary: Color? = null
        compose.setContent {
            WordsTheme {
                darkTheme = isSystemInDarkTheme()
                palette = LocalWordsPalette.current
                primary = MaterialTheme.colorScheme.primary
                Text("palette_probe")
            }
        }
        compose.onNodeWithText("palette_probe").assertExists()
        assertEquals(wordsPalette(darkTheme), palette)
        assertEquals(wordsPalette(darkTheme).primary, primary)
    }

    @Test
    fun compositionLocalChange_recomposesConsumers() {
        val palette = mutableStateOf(LightWordsPalette)
        compose.setContent {
            CompositionLocalProvider(LocalWordsPalette provides palette.value) {
                Text(if (LocalWordsPalette.current == DarkWordsPalette) "dark_probe" else "light_probe")
            }
        }
        compose.onNodeWithText("light_probe").assertExists()
        compose.runOnIdle { palette.value = DarkWordsPalette }
        compose.onNodeWithText("dark_probe").assertExists()
    }

    @Test
    fun darkPalette_textContrastMeetsRequirement() {
        val p = DarkWordsPalette
        assertText("text/background", p.text, p.background)
        assertText("text/wellBackground", p.text, p.wellBackground)
        assertText("text/tableHead", p.text, p.tableHead)
        assertText("text/tableFoot", p.text, p.tableFoot)
        assertText("navbarActive/navbar", p.navbarActive, p.navbar)
        assertText("defaultButtonText/defaultButton", p.defaultButtonText, p.defaultButton)
        assertText("background/primary button", p.background, p.primary)
        assertText("background/primaryPressed", p.background, p.primaryPressed)
        assertText("primary on open slot", p.primary, p.primary.copy(alpha = 0.12f).over(p.background))
        for (kind in listOf("Success", "Info", "Warning", "Danger")) {
            val text = when (kind) {
                "Success" -> p.alertSuccessText
                "Info" -> p.alertInfoText
                "Warning" -> p.alertWarningText
                else -> p.alertDangerText
            }
            val background = when (kind) {
                "Success" -> p.alertSuccessBackground
                "Info" -> p.alertInfoBackground
                "Warning" -> p.alertWarningBackground
                else -> p.alertDangerBackground
            }
            assertText("alert$kind text", text, background)
        }
        for ((name, surface) in levelSurfaces(p)) {
            assertText("level $name text", p.text, surface)
        }
    }

    @Test
    fun darkPalette_significantElementsMeetThreeToOne() {
        val p = DarkWordsPalette
        assertUi("input focus border", p.inputFocusBorder, p.background)
        assertUi("unfocused border", p.defaultButtonBorder, p.background)
        assertUi("level card border", p.defaultButtonBorder, p.levelAvailableBackground)
        assertUi("tile border", p.tileBorder, p.background)
        assertUi("well border", p.wellBorder, p.wellBackground)
        assertUi("progress track", p.wellBorder, p.background)
        assertUi("danger outline", p.dangerButton, p.background)
        assertUi("open slot border", p.primary, p.background)
        assertUi(
            "threshold marker",
            p.starEmpty.copy(alpha = 0.6f).over(p.levelAvailableBackground),
            p.levelAvailableBackground,
        )
        for ((name, surface) in levelSurfaces(p)) {
            assertUi("level $name starDone", p.starDone, surface)
            assertUi("level $name starEmpty", p.starEmpty, surface)
        }
        assertUi("alertSuccess border", p.alertSuccessBorder, p.alertSuccessBackground)
        assertUi("alertInfo border", p.alertInfoBorder, p.alertInfoBackground)
        assertUi("alertWarning border", p.alertWarningBorder, p.alertWarningBackground)
        assertUi("alertDanger border", p.alertDangerBorder, p.alertDangerBackground)
    }

    @Test
    fun lightPalette_textContrastMeetsRequirement() {
        val p = LightWordsPalette
        assertText("text/background", p.text, p.background)
        assertText("text/wellBackground", p.text, p.wellBackground)
        assertText("text/tableHead", p.text, p.tableHead)
        assertText("text/tableFoot", p.text, p.tableFoot)
        assertText("navbarActive/navbar", p.navbarActive, p.navbar)
        assertText("defaultButtonText/defaultButton", p.defaultButtonText, p.defaultButton)
        assertText("background/primary button", p.background, p.primary)
        assertText("background/primaryPressed", p.background, p.primaryPressed)
        for (kind in listOf("Success", "Info", "Warning", "Danger")) {
            val text = when (kind) {
                "Success" -> p.alertSuccessText
                "Info" -> p.alertInfoText
                "Warning" -> p.alertWarningText
                else -> p.alertDangerText
            }
            val background = when (kind) {
                "Success" -> p.alertSuccessBackground
                "Info" -> p.alertInfoBackground
                "Warning" -> p.alertWarningBackground
                else -> p.alertDangerBackground
            }
            assertText("alert$kind text", text, background)
        }
        for ((name, surface) in levelSurfaces(p)) {
            assertText("level $name text", p.text, surface)
        }
    }

    @Test
    fun openSlotLetterStaysDistinguishableInBothThemes() {
        val light = LightWordsPalette
        val dark = DarkWordsPalette
        // Светлый акцент унаследован из v1: на 12% заливке даёт 3,92:1 (порог 3:1
        // для значимого элемента). Тёмный акцент даёт 7,26:1 (порог 4,5:1).
        assertUi("light primary on open slot",
            light.primary, light.primary.copy(alpha = 0.12f).over(light.background))
        assertText("dark primary on open slot",
            dark.primary, dark.primary.copy(alpha = 0.12f).over(dark.background))
    }

    @Test
    fun bothPalettes_preserveSemanticColors() {
        for ((theme, p) in listOf("light" to LightWordsPalette, "dark" to DarkWordsPalette)) {
            assertTrue("$theme: successButton is green",
                p.successButton.green > p.successButton.red && p.successButton.green > p.successButton.blue)
            assertTrue("$theme: dangerButton is red",
                p.dangerButton.red > p.dangerButton.green && p.dangerButton.red > p.dangerButton.blue)
            assertTrue("$theme: alertSuccess is green",
                p.alertSuccessText.green > p.alertSuccessText.red && p.alertSuccessText.green > p.alertSuccessText.blue)
            assertTrue("$theme: alertDanger is red",
                p.alertDangerText.red > p.alertDangerText.green && p.alertDangerText.red > p.alertDangerText.blue)
            assertTrue("$theme: alertWarning is amber",
                p.alertWarningText.red > p.alertWarningText.blue && p.alertWarningText.green > p.alertWarningText.blue)
            assertTrue("$theme: alertInfo is blue",
                p.alertInfoText.blue > p.alertInfoText.red && p.alertInfoText.blue > p.alertInfoText.green)
            assertTrue("$theme: success and danger remain distinct", p.successButton != p.dangerButton)
        }
    }

    @Test
    fun levelStateBackgrounds_followRequiredSchemeInBothThemes() {
        for ((theme, p) in listOf("light" to LightWordsPalette, "dark" to DarkWordsPalette)) {
            val locked = p.levelLockedBackground.over(p.background)
            val available = p.levelAvailableBackground.over(p.background)
            val passed = p.levelPassedBackground.over(p.background)
            val allStars = p.levelAllStarsBackground.over(p.background)

            // Закрытый и доступный — нейтральные серые/поверхности, без красного или розового.
            assertTrue("$theme: locked is neutral", locked.isNeutral())
            assertTrue("$theme: available is neutral", available.isNeutral())
            assertTrue("$theme: locked is not red/pink",
                locked.red <= locked.green + 0.02f && locked.blue <= locked.green + 0.02f)
            assertTrue("$theme: locked is dimmer than available",
                locked.luminance() < available.luminance())
            if (theme == "light") {
                assertTrue("$theme: available is light", available.luminance() > 0.9f)
            } else {
                assertTrue("$theme: available is a dark surface", available.luminance() < 0.1f)
            }

            // Пройденный — зелёный, не синий; все звёзды — золотой/янтарный.
            assertTrue("$theme: passed is green",
                passed.green > passed.red + 0.02f && passed.green > passed.blue + 0.02f)
            assertTrue("$theme: all stars is gold/amber",
                allStars.red > allStars.blue + 0.05f && allStars.green > allStars.blue + 0.05f
                    && allStars.red >= allStars.green)

            // Все четыре состояния остаются визуально различными.
            val distinct = listOf(locked, available, passed, allStars)
                .map { Triple(it.red, it.green, it.blue) }.toSet()
            assertEquals("$theme: four distinct level backgrounds", 4, distinct.size)
        }
    }

    private fun Color.isNeutral() =
        maxOf(red, green, blue) - minOf(red, green, blue) < 0.02f

    private fun levelSurfaces(p: WordsPalette) = listOf(
        "available" to p.levelAvailableBackground,
        "locked" to p.levelLockedBackground,
        "passed" to p.levelPassedBackground.over(p.background),
        "allStars" to p.levelAllStarsBackground.over(p.background),
    )

    /** Фактический цвет полупрозрачного слоя на подложке. */
    private fun Color.over(background: Color) = Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )

    private fun Color.luminance(): Float {
        fun channel(value: Float) =
            if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.luminance()
        val second = background.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private fun assertText(name: String, foreground: Color, background: Color) {
        val value = contrast(foreground, background)
        assertTrue("$name: $value < 4.5", value >= 4.5f)
    }

    private fun assertUi(name: String, foreground: Color, background: Color) {
        val value = contrast(foreground, background)
        assertTrue("$name: $value < 3", value >= 3f)
    }

    private fun assertSchemeMatches(scheme: ColorScheme, palette: WordsPalette) {
        assertEquals(palette.primary, scheme.primary)
        assertEquals(palette.background, scheme.onPrimary)
        assertEquals(palette.wellBackground, scheme.primaryContainer)
        assertEquals(palette.text, scheme.onPrimaryContainer)
        assertEquals(palette.primaryPressed, scheme.secondary)
        assertEquals(palette.background, scheme.onSecondary)
        assertEquals(palette.background, scheme.background)
        assertEquals(palette.text, scheme.onBackground)
        assertEquals(palette.background, scheme.surface)
        assertEquals(palette.text, scheme.onSurface)
        assertEquals(palette.wellBackground, scheme.surfaceVariant)
        assertEquals(palette.text, scheme.onSurfaceVariant)
        assertEquals(palette.defaultButtonBorder, scheme.outline)
        assertEquals(palette.wellBorder, scheme.outlineVariant)
        assertEquals(palette.alertDangerText, scheme.error)
        assertEquals(palette.background, scheme.onError)
        assertEquals(palette.alertDangerBackground, scheme.errorContainer)
        assertEquals(palette.alertDangerText, scheme.onErrorContainer)
    }
}
