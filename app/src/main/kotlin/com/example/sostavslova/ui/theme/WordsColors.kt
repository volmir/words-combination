package com.example.sostavslova.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Эталонные значения светлой палитры; используются как источник [LightWordsPalette]. */
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
    val levelAvailableBackground = Color(0xFFFFFFFF)
    /** Нейтральный серый вместо цвета ошибки: закрытый уровень не должен выглядеть сбоем. */
    val levelLockedBackground = Color(0xFFE9E9E9)
    /** Зелёный — звёзд не меньше порога открытия следующего уровня. */
    val levelPassedBackground = successButton.copy(alpha = 0.18f)
    val wellBackground = Color(0xFFF5F5F5)
    val wellBorder = Color(0xFFE3E3E3)
    val inputFocusBorder = Color(0xFF66AFE9)
    val tileBorder = Color(0xFFB7CCCE)
    /** Контраст на фактическом белом фоне: 3,88:1 (порог 3:1 для значимых индикаторов). */
    val starDone = Color(0xFFC46A00)
    val levelAllStarsBackground = Color(0xFFE6A100).copy(alpha = 0.20f)
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

/** Полный набор семантических цветовых токенов игрового интерфейса. */
data class WordsPalette(
    val navbar: Color,
    val navbarText: Color,
    val navbarActive: Color,
    val primary: Color,
    val primaryPressed: Color,
    val defaultButton: Color,
    val defaultButtonBorder: Color,
    val defaultButtonText: Color,
    val dangerButton: Color,
    val successButton: Color,
    val levelAvailableBackground: Color,
    val levelLockedBackground: Color,
    val levelPassedBackground: Color,
    val wellBackground: Color,
    val wellBorder: Color,
    val inputFocusBorder: Color,
    val tileBorder: Color,
    val starDone: Color,
    val levelAllStarsBackground: Color,
    val starEmpty: Color,
    val tableHead: Color,
    val tableFoot: Color,
    val alertSuccessBackground: Color,
    val alertSuccessText: Color,
    val alertSuccessBorder: Color,
    val alertInfoBackground: Color,
    val alertInfoText: Color,
    val alertInfoBorder: Color,
    val alertWarningBackground: Color,
    val alertWarningText: Color,
    val alertWarningBorder: Color,
    val alertDangerBackground: Color,
    val alertDangerText: Color,
    val alertDangerBorder: Color,
    val labelBackground: Color,
    val labelText: Color,
    val blockquoteBorder: Color,
    val text: Color,
    val background: Color,
)

/** Светлая палитра: значения берутся из исходного [WordsColors] без изменений. */
val LightWordsPalette = WordsPalette(
    navbar = WordsColors.navbar,
    navbarText = WordsColors.navbarText,
    navbarActive = WordsColors.navbarActive,
    primary = WordsColors.primary,
    primaryPressed = WordsColors.primaryPressed,
    defaultButton = WordsColors.defaultButton,
    defaultButtonBorder = WordsColors.defaultButtonBorder,
    defaultButtonText = WordsColors.defaultButtonText,
    dangerButton = WordsColors.dangerButton,
    successButton = WordsColors.successButton,
    levelAvailableBackground = WordsColors.levelAvailableBackground,
    levelLockedBackground = WordsColors.levelLockedBackground,
    levelPassedBackground = WordsColors.levelPassedBackground,
    wellBackground = WordsColors.wellBackground,
    wellBorder = WordsColors.wellBorder,
    inputFocusBorder = WordsColors.inputFocusBorder,
    tileBorder = WordsColors.tileBorder,
    starDone = WordsColors.starDone,
    levelAllStarsBackground = WordsColors.levelAllStarsBackground,
    starEmpty = WordsColors.starEmpty,
    tableHead = WordsColors.tableHead,
    tableFoot = WordsColors.tableFoot,
    alertSuccessBackground = WordsColors.alertSuccessBackground,
    alertSuccessText = WordsColors.alertSuccessText,
    alertSuccessBorder = WordsColors.alertSuccessBorder,
    alertInfoBackground = WordsColors.alertInfoBackground,
    alertInfoText = WordsColors.alertInfoText,
    alertInfoBorder = WordsColors.alertInfoBorder,
    alertWarningBackground = WordsColors.alertWarningBackground,
    alertWarningText = WordsColors.alertWarningText,
    alertWarningBorder = WordsColors.alertWarningBorder,
    alertDangerBackground = WordsColors.alertDangerBackground,
    alertDangerText = WordsColors.alertDangerText,
    alertDangerBorder = WordsColors.alertDangerBorder,
    labelBackground = WordsColors.labelBackground,
    labelText = WordsColors.labelText,
    blockquoteBorder = WordsColors.blockquoteBorder,
    text = WordsColors.text,
    background = WordsColors.background,
)

/** Тёмная палитра: те же токены, адаптированные к тёмной поверхности. */
val DarkWordsPalette = WordsPalette(
    navbar = Color(0xFF121212),
    navbarText = Color(0xFF9E9E9E),
    navbarActive = Color(0xFFFFFFFF),
    primary = Color(0xFF8AB4F8),
    primaryPressed = Color(0xFF6C9ED0),
    defaultButton = Color(0xFF2C2C2C),
    defaultButtonBorder = Color(0xFF757575),
    defaultButtonText = Color(0xFFE0E0E0),
    dangerButton = Color(0xFFEF6A65),
    successButton = Color(0xFF81C784),
    levelAvailableBackground = Color(0xFF2C2C2C),
    levelLockedBackground = Color(0xFF222222),
    levelPassedBackground = Color(0xFF81C784).copy(alpha = 0.22f),
    wellBackground = Color(0xFF232323),
    wellBorder = Color(0xFF6E6E6E),
    inputFocusBorder = Color(0xFF8AB4F8),
    tileBorder = Color(0xFF5A7A7D),
    starDone = Color(0xFFFFA726),
    levelAllStarsBackground = Color(0xFFFFB300).copy(alpha = 0.24f),
    starEmpty = Color(0xFFAAAAAA),
    tableHead = Color(0xFF333333),
    tableFoot = Color(0xFF2A2A2A),
    alertSuccessBackground = Color(0xFF1B3A24),
    alertSuccessText = Color(0xFFA5D6A7),
    alertSuccessBorder = Color(0xFF4E8E57),
    alertInfoBackground = Color(0xFF16354A),
    alertInfoText = Color(0xFF90CAF9),
    alertInfoBorder = Color(0xFF5095BC),
    alertWarningBackground = Color(0xFF3B3518),
    alertWarningText = Color(0xFFFFD54F),
    alertWarningBorder = Color(0xFFA58F3A),
    alertDangerBackground = Color(0xFF4A2020),
    alertDangerText = Color(0xFFFFA4A2),
    alertDangerBorder = Color(0xFFB96060),
    labelBackground = Color(0xFF555555),
    labelText = Color(0xFFFFFFFF),
    blockquoteBorder = Color(0xFF6E6E6E),
    text = Color(0xFFE0E0E0),
    background = Color(0xFF121212),
)

/** Активная палитра: изменение значения вызывает рекомпозицию потребителей. */
val LocalWordsPalette = compositionLocalOf { LightWordsPalette }

/** Единственный источник выбора палитры — системный night mode. */
internal fun wordsPalette(isDarkTheme: Boolean): WordsPalette =
    if (isDarkTheme) DarkWordsPalette else LightWordsPalette
