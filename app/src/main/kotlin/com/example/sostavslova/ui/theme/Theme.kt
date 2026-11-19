package com.example.sostavslova.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Карточки и информационные панели. */
val WordsCardShape = RoundedCornerShape(10.dp)

/** Кнопки и поля ввода. */
val WordsControlShape = RoundedCornerShape(8.dp)

private fun ColorScheme.withWordsPalette(palette: WordsPalette) = copy(
    primary = palette.primary,
    onPrimary = palette.background,
    primaryContainer = palette.wellBackground,
    onPrimaryContainer = palette.text,
    secondary = palette.primaryPressed,
    onSecondary = palette.background,
    background = palette.background,
    onBackground = palette.text,
    surface = palette.background,
    onSurface = palette.text,
    surfaceVariant = palette.wellBackground,
    onSurfaceVariant = palette.text,
    outline = palette.defaultButtonBorder,
    outlineVariant = palette.wellBorder,
    error = palette.alertDangerText,
    onError = palette.background,
    errorContainer = palette.alertDangerBackground,
    onErrorContainer = palette.alertDangerText,
)

/** Material 3-схема для выбранного системного night mode, согласованная с игровой палитрой. */
internal fun wordsColorScheme(isDarkTheme: Boolean): ColorScheme {
    val base = if (isDarkTheme) darkColorScheme() else lightColorScheme()
    return base.withWordsPalette(wordsPalette(isDarkTheme))
}

private val typography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun WordsTheme(content: @Composable () -> Unit) {
    val isDarkTheme = isSystemInDarkTheme()
    CompositionLocalProvider(LocalWordsPalette provides wordsPalette(isDarkTheme)) {
        MaterialTheme(
            colorScheme = wordsColorScheme(isDarkTheme),
            typography = typography,
            shapes = Shapes(small = WordsControlShape, medium = WordsCardShape, large = WordsCardShape),
            content = content,
        )
    }
}
