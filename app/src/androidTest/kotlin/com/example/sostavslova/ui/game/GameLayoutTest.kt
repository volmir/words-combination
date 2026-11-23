package com.example.sostavslova.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.sostavslova.core.*
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.components.LetterSlots
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class GameLayoutTest {
    @get:Rule val compose = createComposeRule()

    private data class SlotsCase(val width: Int, val fontScale: Float, val pattern: String)

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

    @Test fun largestInstalledLevelFitsNarrowScreenAtDoubleFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val index = WordIndex(context.assets.open("words.txt").bufferedReader().use { it.readLines() })
        val games = campaign.levels.map { it to GameState.start(it.word, index) }
        val (level, initialGame) = games.maxBy { it.second.total }
        val game = initialGame.copy(found = initialGame.allWords)
        assertEquals("Largest installed level", 632, game.foundCount)
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
