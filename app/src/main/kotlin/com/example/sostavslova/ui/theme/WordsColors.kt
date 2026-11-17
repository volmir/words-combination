package com.example.sostavslova.ui.theme

import androidx.compose.ui.graphics.Color

/** Токены из docs/DESIGN.md; без динамических цветов и тёмной темы. */
object WordsColors {
    val navbar = Color(0xFF222222)
    val navbarText = Color(0xFF9D9D9D)
    val navbarActive = Color(0xFFFFFFFF)
    val primary = Color(0xFF337AB7)
    val primaryPressed = Color(0xFF286090)
    val defaultButton = Color(0xFFFFFFFF)
    val defaultButtonBorder = Color(0xFFCCCCCC)
    val defaultButtonText = Color(0xFF333333)
    val dangerButton = Color(0xFFD9534F)
    val successButton = Color(0xFF5CB85C)
    val levelAvailableBackground = successButton.copy(alpha = 0.06f)
    val levelLockedBackground = dangerButton.copy(alpha = 0.04f)
    val levelPassedBackground = primary.copy(alpha = 0.06f)
    val wellBackground = Color(0xFFF5F5F5)
    val wellBorder = Color(0xFFE3E3E3)
    val inputFocusBorder = Color(0xFF66AFE9)
    val tileBorder = Color(0xFFB7CCCE)
    /** Контраст на фактическом белом фоне: 3,88:1 (порог 3:1 для значимых индикаторов). */
    val starDone = Color(0xFFC46A00)
    val levelAllStarsBackground = starDone.copy(alpha = 0.05f)
    /** Контраст на фактическом белом фоне: 4,48:1. */
    val starEmpty = Color(0xFF777777)
    val tableHead = Color(0xFFE8E8E8)
    val tableFoot = Color(0xFFF1F1F1)
    val alertSuccessBackground = Color(0xFFDFF0D8)
    val alertSuccessText = Color(0xFF3C763D)
    val alertSuccessBorder = Color(0xFFD6E9C6)
    val alertInfoBackground = Color(0xFFD9EDF7)
    val alertInfoText = Color(0xFF31708F)
    val alertInfoBorder = Color(0xFFBCE8F1)
    val alertWarningBackground = Color(0xFFFCF8E3)
    val alertWarningText = Color(0xFF8A6D3B)
    val alertWarningBorder = Color(0xFFFAEBCC)
    val alertDangerBackground = Color(0xFFF2DEDE)
    val alertDangerText = Color(0xFFA94442)
    val alertDangerBorder = Color(0xFFEBCCD1)
    val labelBackground = Color(0xFF777777)
    val labelText = Color(0xFFFFFFFF)
    val blockquoteBorder = Color(0xFFEEEEEE)
    val text = Color(0xFF333333)
    val background = Color(0xFFFFFFFF)
}
