package com.example.sostavslova.ui.game

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.*
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.data.InputMode
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.LetterSlots
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.DarkWordsPalette
import com.example.sostavslova.ui.theme.LightWordsPalette
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsPalette
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class GameLayoutTest {
    @get:Rule val compose = createComposeRule()

    private data class SlotsCase(val width: Int, val fontScale: Float, val pattern: String)

    private data class InputGeometryCase(
        val width: Int,
        val fontScale: Float,
        val mode: InputMode,
        val dark: Boolean,
    ) {
        override fun toString() = "${width}dp, fontScale=$fontScale, $mode, ${if (dark) "dark" else "light"}"
    }

    @Test fun compactHintCaptionAndControlsFitNarrowScreensAtDoubleFontScale() {
        data class Case(val width: Int, val scale: Float, val open: Int)
        val cases = listOf(320, 360, 411).flatMap { width ->
            listOf(1f, 2f).flatMap { scale -> listOf(0, 1, 2, 4).map { Case(width, scale, it) } }
        }
        fun snapshot(case: Case): GameUiState {
            val game = GameState.start("картотека", WordIndex(listOf("крот", "торт")))
            var current = CurrentCampaignLevel(CampaignLevel(1, game.word, 1, 1,
                listOf(CampaignTask.T1("count", 2), CampaignTask.T4("riddle", listOf("крот")))), game)
            repeat(case.open) { current = HintRules.next(current, random = kotlin.random.Random(17)).state }
            val panel = HintPanelModel.of(current, HintRules.nextCost(current), HintRules.budget(current).remaining, 4)
            return GameUiState(loading = false, game = game, campaignLevel = current, hintPanel = panel)
        }
        val current = mutableStateOf(cases.first() to snapshot(cases.first()))
        compose.setContent {
            val (case, state) = current.value
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, case.scale)) {
                WordsTheme {
                    Box(Modifier.width(case.width.dp)) { GameScreen(state, {}, {}, {}, {}) }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case to snapshot(case) }
            compose.onNodeWithTag("game_hint_caption").performScrollTo()
                .assertTextEquals(if (case.open == 0) "Подсказка ещё не открыта"
                    else "Слово из 4 букв · открыто ${case.open}")
            val change = compose.onNodeWithTag("game_hint_task_change").performScrollTo()
            if (case.open >= 2) change.assertIsNotEnabled() else change.assertIsEnabled()
            val changeNode = change.fetchSemanticsNode()
            val heading = compose.onNodeWithTag("game_hint_task").fetchSemanticsNode()
            assertTrue("$case: кнопка должна быть под заголовком",
                changeNode.positionInRoot.y >= heading.positionInRoot.y + heading.size.height - 1f)
            compose.onNodeWithTag("game_hint_button").performScrollTo()
            checkLayout()
            assertTrue("$case: прокрутка внутри панели",
                compose.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("game_hint")),
                    useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            val panel = compose.onNodeWithTag("game_hint").fetchSemanticsNode().boundsInRoot
            compose.onAllNodes(hasAnyAncestor(hasTestTag("game_hint")), useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { node ->
                    assertTrue("$case: элемент обрезан по горизонтали", node.positionInRoot.x >= panel.left - 1f &&
                        node.positionInRoot.x + node.size.width <= panel.right + 1f)
                }
        }
    }

    @Test fun inputControlsAndTilesFitAllWidthsAndFontScalesInBothModes() {
        data class Case(val width: Int, val scale: Float, val word: String,
            val mode: com.example.sostavslova.data.InputMode)
        val cases = listOf(320, 360, 411).flatMap { width ->
            listOf(1f, 2f).flatMap { scale ->
                listOf("картотек", "чемпионство", "благодарность").flatMap { word ->
                    com.example.sostavslova.data.InputMode.entries.map { Case(width, scale, word, it) }
                }
            }
        }
        val current = mutableStateOf(cases.first())
        compose.setContent {
            val case = current.value
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, case.scale)) {
                WordsTheme {
                    Box(Modifier.width(case.width.dp)) {
                        GameScreen(GameUiState(loading = false, game = GameState(case.word, listOf("кот")),
                            input = "кот", inputMode = case.mode), {}, {}, {}, {})
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.onNodeWithTag("game_shuffle").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("game_backspace").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("game_input_mode_toggle").performScrollTo().assertIsDisplayed()
            checkLayout()
            val tiles = case.word.indices.map { index ->
                compose.onNodeWithTag("source_word_tile_${index + 1}", useUnmergedTree = true).fetchSemanticsNode()
            }
            val rows = tiles.groupBy { it.positionInRoot.y.roundToInt() }.values.map { it.size }
            assertTrue("$case: больше трёх строк $rows", rows.size <= 3)
            assertTrue("$case: неровные строки $rows", rows.max() - rows.min() <= 1)
        }
    }

    /**
     * STAB-03: геометрия блока ввода после STAB-02 в реальной вложенности GameScreen + Well.
     * Ширины, масштаб шрифта, режим ввода и тема покрыты по отдельности; не каждый фактор
     * перемножается со всеми, но каждое значение встречается хотя бы один раз.
     * Все координаты сняты одним снимком semantics после одной прокрутки блока, поэтому
     * сдвиг видимой области прокрутки и IME не сравниваются между разными кадрами.
     */
    @Test fun inputBlockGeometryKeepsSubmitAndActionsOrderedAcrossModesWidthsScalesAndThemes() {
        val cases = buildList {
            for ((widthIndex, width) in listOf(320, 360, 411).withIndex()) {
                for (mode in InputMode.entries) {
                    for ((scaleIndex, scale) in listOf(1f, 2f).withIndex()) {
                        add(InputGeometryCase(width, scale, mode, dark = (widthIndex + scaleIndex) % 2 == 1))
                    }
                }
            }
        }
        val current = mutableStateOf(cases.first())
        var palette: WordsPalette? = null
        compose.setContent {
            val case = current.value
            val base = LocalDensity.current
            val configuration = LocalConfiguration.current
            val nightConfiguration = remember(configuration, case.dark) {
                Configuration().apply {
                    setTo(configuration)
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, case.fontScale),
                LocalConfiguration provides nightConfiguration,
            ) {
                WordsTheme {
                    palette = LocalWordsPalette.current
                    Box(Modifier.width(case.width.dp)) {
                        GameScreen(GameUiState(loading = false, game = GameState("картотека", listOf("кот")),
                            input = "кот", inputMode = case.mode,
                            message = GameMessage(R.string.answer_unknown, "кот", type = AlertType.Danger)),
                            {}, {}, {}, {})
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.waitForIdle()
            val context = case.toString()
            assertEquals("$context: активная тема", if (case.dark) DarkWordsPalette else LightWordsPalette, palette)
            val fieldTag = if (case.mode == InputMode.KEYBOARD) "game_input" else "game_input_display"
            val otherFieldTag = if (case.mode == InputMode.KEYBOARD) "game_input_display" else "game_input"

            // Прокручиваем блок один раз, затем снимаем все координаты одним снимком semantics.
            compose.onNodeWithTag(fieldTag).performScrollTo()
            compose.waitForIdle()
            val nodes = compose.onAllNodes(SemanticsMatcher("любой узел") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
            fun tagged(tag: String) = nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

            assertEquals("$context: ровно одно поле ввода", 1, tagged(fieldTag).size)
            assertEquals("$context: поле другого режима не должно отображаться", 0, tagged(otherFieldTag).size)
            assertEquals("$context: ровно одна кнопка проверки", 1, tagged("game_submit").size)
            assertEquals("$context: блок обратной связи", 1, tagged("game_feedback").size)

            val field = tagged(fieldTag).single().boundsInRoot
            val submit = tagged("game_submit").single().boundsInRoot
            val toggle = tagged("game_input_mode_toggle").single().boundsInRoot
            val shuffle = tagged("game_shuffle").single().boundsInRoot
            val backspace = tagged("game_backspace").single().boundsInRoot
            val feedback = tagged("game_feedback").single().boundsInRoot
            val banner = tagged("game_banner").single().boundsInRoot
            val screen = tagged("game_screen").single().boundsInRoot

            val px = compose.density.density
            fun dp(value: Float) = value * px
            val tolerance = dp(1f)
            val rightTolerance = dp(2f)

            // Поле и submit занимают всю доступную ширину Well: 16 dp экрана + 16 dp внутри Well.
            assertEquals("$context: поле на всю доступную ширину", screen.width - dp(64f), field.width, tolerance)
            assertTrue("$context: submit полностью ниже поля", submit.top >= field.bottom - tolerance)
            assertEquals("$context: submit на всю доступную ширину", field.width, submit.width, tolerance)

            for ((name, rect) in listOf("переключатель" to toggle, "перемешивание" to shuffle,
                "стирание" to backspace)) {
                assertTrue("$context: $name должен быть ниже submit", rect.top >= submit.bottom - tolerance)
            }
            // Строка определяется центром, а не фиксированной высотой: при 200% текст может расти.
            val oneRow = abs(toggle.center.y - shuffle.center.y) <= tolerance &&
                abs(shuffle.center.y - backspace.center.y) <= tolerance
            if (case.fontScale <= 1f) {
                assertTrue("$context: при 100% все действия в одной строке", oneRow)
            }
            if (oneRow) {
                assertTrue("$context: порядок переключатель → перемешивание → стирание",
                    toggle.left < shuffle.left && shuffle.left < backspace.left)
                assertTrue("$context: переключатель прижат влево", toggle.left <= field.left + rightTolerance)
                assertTrue("$context: стирание прижато вправо",
                    abs(backspace.right - field.right) <= rightTolerance)
            } else {
                assertTrue("$context: переключатель и перемешивание в одной строке",
                    abs(toggle.center.y - shuffle.center.y) <= tolerance)
                assertTrue("$context: порядок переключатель → перемешивание", toggle.left < shuffle.left)
                assertTrue("$context: стирание ниже верхней строки действий",
                    backspace.top >= maxOf(toggle.bottom, shuffle.bottom) - tolerance)
                assertTrue("$context: переключатель прижат влево", toggle.left <= field.left + rightTolerance)
                assertTrue("$context: стирание прижато вправо и при переносе",
                    abs(backspace.right - field.right) <= rightTolerance)
            }

            val inputRects = listOf("поле" to field, "submit" to submit, "переключатель" to toggle,
                "перемешивание" to shuffle, "стирание" to backspace)
            for (first in inputRects.indices) {
                for (second in first + 1 until inputRects.size) {
                    assertFalse("$context: ${inputRects[first].first} пересекает ${inputRects[second].first}",
                        inputRects[first].second.intersects(inputRects[second].second, tolerance))
                }
            }
            for ((name, rect) in inputRects) {
                assertTrue("$context: $name обрезан слева", rect.left >= field.left - tolerance)
                assertTrue("$context: $name обрезан справа", rect.right <= field.right + tolerance)
                assertTrue("$context: $name выходит за экран",
                    rect.left >= screen.left - tolerance && rect.right <= screen.right + tolerance)
            }
            val minimumTouch = dp(48f) - tolerance
            for ((name, rect) in listOf("submit" to submit, "переключатель" to toggle,
                "перемешивание" to shuffle, "стирание" to backspace)) {
                assertTrue("$context: зона $name меньше 48×48 dp: ${rect.width}×${rect.height}",
                    rect.width >= minimumTouch && rect.height >= minimumTouch)
            }

            val blockBottom = maxOf(field.bottom, submit.bottom, toggle.bottom, shuffle.bottom, backspace.bottom)
            assertTrue("$context: feedback должен быть ниже блока ввода", feedback.top >= blockBottom - tolerance)
            assertTrue("$context: сообщение должно быть ниже блока ввода", banner.top >= blockBottom - tolerance)
            for ((name, rect) in inputRects) {
                assertFalse("$context: feedback перекрывает $name", feedback.intersects(rect, tolerance))
            }
            assertTrue("$context: feedback выходит за экран",
                feedback.left >= screen.left - tolerance && feedback.right <= screen.right + tolerance)
            checkLayout()

            // Видимая область прокрутки может обрезать блок: каждый элемент должен прокручиваться в неё.
            for (tag in listOf(fieldTag, "game_submit", "game_input_mode_toggle", "game_shuffle",
                "game_backspace", "game_feedback")) {
                compose.onNodeWithTag(tag).performScrollTo()
            }
            compose.waitForIdle()
        }
    }

    @Test fun inputFieldSubmitAndActionsKeepVerticalOrderAndOneRowAtNativeScale() {
        data class Case(val width: Int, val mode: com.example.sostavslova.data.InputMode)
        val cases = listOf(320, 360, 411).flatMap { width ->
            com.example.sostavslova.data.InputMode.entries.map { Case(width, it) }
        }
        val current = mutableStateOf(cases.first())
        compose.setContent {
            val case = current.value
            WordsTheme {
                Box(Modifier.width(case.width.dp)) {
                    GameScreen(GameUiState(loading = false, game = GameState("картотека", listOf("кот")),
                        input = "кот", inputMode = case.mode), {}, {}, {}, {})
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.waitForIdle()
            val fieldTag = if (case.mode == com.example.sostavslova.data.InputMode.KEYBOARD) "game_input"
                else "game_input_display"
            val field = compose.onNodeWithTag(fieldTag).performScrollTo().fetchSemanticsNode().boundsInRoot
            val submit = compose.onNodeWithTag("game_submit").fetchSemanticsNode().boundsInRoot
            val toggle = compose.onNodeWithTag("game_input_mode_toggle").fetchSemanticsNode().boundsInRoot
            val shuffle = compose.onNodeWithTag("game_shuffle").fetchSemanticsNode().boundsInRoot
            val backspace = compose.onNodeWithTag("game_backspace").fetchSemanticsNode().boundsInRoot
            val context = "${case.width}dp, ${case.mode}"
            assertTrue("$context: поле и кнопка должны идти сверху вниз", submit.top >= field.bottom - 1f)
            assertEquals("$context: ширина кнопки", field.width, submit.width, 1f)
            assertTrue("$context: действия должны быть ниже кнопки", toggle.top >= submit.bottom - 1f)
            assertTrue("$context: действия должны быть в одной строке",
                kotlin.math.abs(toggle.top - shuffle.top) <= 1f && kotlin.math.abs(shuffle.top - backspace.top) <= 1f)
            assertTrue("$context: порядок переключатель, перемешивание, стирание",
                toggle.left < shuffle.left && shuffle.left < backspace.left)
            assertTrue("$context: переключатель должен быть слева", toggle.left <= field.left + 1f)
            assertTrue("$context: стирание должно быть справа", backspace.right >= field.right - 1f)
        }
    }

    @Test fun largestInstalledLevelFitsNarrowScreenAtDoubleFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val index = WordIndex(context.assets.open("words.txt").bufferedReader().use { it.readLines() })
        val games = campaign.levels.map { it to GameState.start(it.word, index) }
        val (level, initialGame) = games.maxBy { it.second.total }
        val game = initialGame.copy(found = initialGame.allWords)
        assertEquals("Largest installed level", 631, game.foundCount)
        val snapshot = GameUiState(loading = false, game = game, statistics = game.statistics(),
            countMinLength = campaign.countMinLength,
            totalLevels = campaign.levels.size, campaignLevel = CurrentCampaignLevel(level, game),
            hintPanel = HintPanelModel.of(CurrentCampaignLevel(level, game), null, 0, campaign.countMinLength))
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                WordsTheme {
                    Box(Modifier.width(320.dp).background(Color.White)) {
                        GameScreen(snapshot, {}, {}, {}, {})
                    }
                }
            }
        }
        val counted = game.countedFoundCount(campaign.countMinLength)
        compose.onNodeWithTag("game_found_counted").performScrollTo()
            .assertTextEquals("Учитывается в заданиях: $counted ${RussianPlural.words(counted)} (от ${campaign.countMinLength} букв)")
        compose.onAllNodesWithTag("game_found_counted_note").assertCountEquals(1)
        compose.onNodeWithTag("game_found_counted_note").performScrollTo()
            .assertTextEquals("Слова короче ${campaign.countMinLength} букв записываются в найденные, но в заданиях на число слов и на букву не учитываются.")
        compose.onAllNodesWithText("не учитываются", substring = true).assertCountEquals(1)
        checkLayout()
        for (row in snapshot.statistics.rows) {
            val group = compose.onNodeWithTag("level_found_group_${row.length}")
            group.performScrollTo()
            if (group.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) != "Раскрыта") {
                group.performClick()
            }
            compose.onNodeWithTag("game_found_words_${row.length}").assertExists()
            val expectedTitle = "${row.length} ${RussianPlural.letters(row.length)} · ${row.found} из ${row.total}"
            compose.onNodeWithTag("level_found_group_${row.length}")
                .assertTextEquals(expectedTitle, "Свернуть")
            checkLayout()
        }
        checkLayout()
        val screen = compose.onNodeWithTag("game_screen").fetchSemanticsNode().boundsInRoot
        compose.onAllNodes(hasAnyAncestor(hasTestTag("game_found_words")), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val left = node.positionInRoot.x
                val right = left + node.size.width
                assertTrue("Элемент выходит за экран 320 dp: $left..$right",
                    left >= screen.left - 1f && right <= screen.right + 1f)
            }
    }

    @Test fun letterSlotsStayVisibleAtNarrowWidthsAndLargeFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val longest = campaign.levels.maxBy { it.word.length }.word
        val words = listOf("а".repeat(8), "а".repeat(11), "а".repeat(13))
        val halfOpen = words.map { word ->
            word.mapIndexed { index, char -> if (index % 2 == 0) char else '_' }.joinToString("")
        }
        val patterns = halfOpen + longest
        val cases = listOf(320, 360, 411).flatMap { width ->
            listOf(1f, 2f).flatMap { fontScale ->
                patterns.map { SlotsCase(width, fontScale, it) }
            }
        }
        val current = mutableStateOf(cases.first())
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, current.value.fontScale)) {
                WordsTheme {
                    Box(Modifier.width(current.value.width.dp).background(Color.White).padding(16.dp)) {
                        Well {
                            LetterSlots(current.value.pattern,
                                Modifier.fillMaxWidth().testTag("game_hint_pattern"))
                        }
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.waitForIdle()
            verifySlots(case)
        }
    }

    private fun verifySlots(case: SlotsCase) {
        val density = compose.density.density
        val patternNode = compose.onNodeWithTag("game_hint_pattern").fetchSemanticsNode()
        val parent = patternNode.boundsInRoot
        val slots = case.pattern.indices.map { index ->
            compose.onNodeWithTag("game_hint_slot_${index + 1}", useUnmergedTree = true).fetchSemanticsNode()
        }
        val context = "${case.width}dp, fontScale=${case.fontScale}, ${case.pattern}"
        assertEquals(context, case.pattern.length, slots.size)
        val minimum = 28f * density - 1f
        val maximum = 36f * density + 1f
        slots.forEachIndexed { index, node ->
            assertTrue("$context: слот ${index + 1} слишком мал: ${node.size}",
                node.size.width >= minimum && node.size.height >= minimum)
            assertTrue("$context: слот ${index + 1} шире 36 dp: ${node.size}",
                node.size.width <= maximum && node.size.height <= maximum)
            val bounds = node.boundsInRoot
            assertTrue("$context: слот ${index + 1} обрезан слева", bounds.left >= parent.left - 1f)
            assertTrue("$context: слот ${index + 1} обрезан справа", bounds.right <= parent.right + 1f)
        }
        val rows = slots.groupBy { it.boundsInRoot.top.roundToInt() }.values.map { it.size }
        assertTrue("$context: неровные строки $rows", rows.max() - rows.min() <= 1)
        assertEquals("$context: потеряны ячейки", case.pattern.length, rows.sum())
        when (case.pattern.length) {
            8 -> assertEquals("$context: ряд из 8", listOf(8), rows)
            11 -> assertEquals("$context: строки 6+5", listOf(6, 5), rows)
            13 -> assertEquals("$context: строки 7+6", listOf(7, 6), rows)
        }
        assertEquals("$context: описание ряда", "Слово из ${case.pattern.length} ${RussianPlural.form(case.pattern.length, "буквы", "букв", "букв")}. Открыто: " +
            case.pattern.mapIndexedNotNull { index, char ->
                if (char == '_') null else "${index + 1}-я ${char.uppercaseChar()}"
            }.joinToString(", ") + ".",
            patternNode.config[SemanticsProperties.ContentDescription].joinToString(""))
        for (node in compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                hasAnyAncestor(hasTestTag("game_hint_pattern")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { assertTextLayoutFits(it, context) }
        }
    }

    /** Пересечение с площадью больше допуска: касание границ наложением не считается. */
    private fun Rect.intersects(other: Rect, tolerance: Float): Boolean {
        val overlapWidth = minOf(right, other.right) - maxOf(left, other.left)
        val overlapHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return overlapWidth > tolerance && overlapHeight > tolerance
    }

    private fun checkLayout() {
        for (node in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                val text = layout.layoutInput.text.text
                assertFalse(text, layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse(text, layout.isLineEllipsized(line))
                    assertTrue(text, layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue(text, layout.getLineLeft(line) >= -1f)
                    assertTrue(text, layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
        for (node in compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val minimum = 48f * compose.density.density - 1f
            assertTrue(node.size.toString(), node.size.width >= minimum && node.size.height >= minimum)
        }
    }
}
