package com.example.sostavslova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.theme.WordsColors

enum class AlertType { Success, Info, Warning, Danger }

@Composable
fun AlertBanner(type: AlertType, text: String, modifier: Modifier = Modifier) {
    val (background, foreground, border) = when (type) {
        AlertType.Success -> Triple(WordsColors.alertSuccessBackground, WordsColors.alertSuccessText, WordsColors.alertSuccessBorder)
        AlertType.Info -> Triple(WordsColors.alertInfoBackground, WordsColors.alertInfoText, WordsColors.alertInfoBorder)
        AlertType.Warning -> Triple(WordsColors.alertWarningBackground, WordsColors.alertWarningText, WordsColors.alertWarningBorder)
        AlertType.Danger -> Triple(WordsColors.alertDangerBackground, WordsColors.alertDangerText, WordsColors.alertDangerBorder)
    }
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(4.dp), color = background, contentColor = foreground,
        border = BorderStroke(1.dp, border),
    ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
}
