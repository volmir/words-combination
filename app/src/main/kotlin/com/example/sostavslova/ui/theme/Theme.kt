package com.example.sostavslova.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Карточки и информационные панели. */
val WordsCardShape = RoundedCornerShape(10.dp)

/** Кнопки и поля ввода. */
val WordsControlShape = RoundedCornerShape(8.dp)

private val colorScheme = lightColorScheme(
    primary = WordsColors.primary,
    onPrimary = WordsColors.background,
    primaryContainer = WordsColors.wellBackground,
    onPrimaryContainer = WordsColors.text,
    secondary = WordsColors.primaryPressed,
    onSecondary = WordsColors.background,
    background = WordsColors.background,
    onBackground = WordsColors.text,
    surface = WordsColors.background,
    onSurface = WordsColors.text,
    surfaceVariant = WordsColors.wellBackground,
    onSurfaceVariant = WordsColors.text,
    outline = WordsColors.defaultButtonBorder,
    outlineVariant = WordsColors.wellBorder,
    error = WordsColors.alertDangerText,
    onError = WordsColors.background,
    errorContainer = WordsColors.alertDangerBackground,
    onErrorContainer = WordsColors.alertDangerText,
)

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
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = Shapes(small = WordsControlShape, medium = WordsCardShape, large = WordsCardShape),
        content = content,
    )
}
