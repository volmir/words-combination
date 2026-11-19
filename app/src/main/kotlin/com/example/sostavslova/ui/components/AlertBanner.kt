package com.example.sostavslova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.theme.WordsCardShape
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsPalette

enum class AlertType { Success, Info, Warning, Danger }

/** Фон, текст и граница баннера берутся только из активной палитры. */
internal fun alertColors(palette: WordsPalette, type: AlertType): Triple<Color, Color, Color> =
    when (type) {
        AlertType.Success -> Triple(palette.alertSuccessBackground, palette.alertSuccessText, palette.alertSuccessBorder)
        AlertType.Info -> Triple(palette.alertInfoBackground, palette.alertInfoText, palette.alertInfoBorder)
        AlertType.Warning -> Triple(palette.alertWarningBackground, palette.alertWarningText, palette.alertWarningBorder)
        AlertType.Danger -> Triple(palette.alertDangerBackground, palette.alertDangerText, palette.alertDangerBorder)
    }

@Composable
fun AlertBanner(type: AlertType, text: String, modifier: Modifier = Modifier) {
    val (background, foreground, border) = alertColors(LocalWordsPalette.current, type)
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = WordsCardShape, color = background, contentColor = foreground,
        border = BorderStroke(1.dp, border),
    ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
}
