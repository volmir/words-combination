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
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
)

@Composable
fun WordsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = Shapes(small = RoundedCornerShape(4.dp)),
        content = content,
    )
}
