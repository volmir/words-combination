package com.example.sostavslova.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CampaignGameUiTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private val tasks = listOf(
        CampaignTask.T1("count", 2), CampaignTask.T2("initial", 'к', 1),
        CampaignTask.T3("long", 4, "карта"), CampaignTask.T4("riddles", listOf("кот", "крот")),
        CampaignTask.T5("ending", "та", 4), CampaignTask.T6("theme", "animals", 1, listOf("кот", "крот")),
        CampaignTask.T7("contains", 'р', 1),
    )
    private val game = GameState("картотека", listOf("кот", "крот", "карта"), listOf("кот"))
    private val evaluated = TaskRules.evaluate(CurrentCampaignLevel(CampaignLevel(30, game.word, 6, 4, tasks), game), 4)
    private val initial = GameUiState(loading = false, game = game, campaignLevel = evaluated.state,
        totalLevels = 30, taskProgress = evaluated.progress, statistics = game.statistics(),
        riddles = mapOf("riddles" to listOf(
            RiddleUiState("Условное безопасное описание животного", 3, "К", solved = true),
            RiddleUiState(null, 4, "К"))),
    )

    @Test fun allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes() {
        val state = mutableStateOf(initial)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, {}, { state.value = state.value.copy(dialog = it) },
                            { state.value = state.value.copy(dialog = null) }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("level_header").assertTextEquals("Мастер · уровень 30 из 30")
        compose.onNodeWithTag("level_difficulty").assertTextContains("Для финала", substring = true)
        compose.onNodeWithTag("game_tasks_summary").performScrollTo()
            .assertTextEquals("Задания · ${evaluated.state.completedTaskIds.size} из ${tasks.size}")
        compose.onNodeWithTag("game_tasks_toggle").assertDoesNotExist()
        compose.onNodeWithTag("game_riddle_preview").assertDoesNotExist()
        compose.onNodeWithText("Задания").assertDoesNotExist()
        TaskType.entries.forEach { type ->
            compose.onNodeWithTag("level_task_${type.name.lowercase()}").performScrollTo().assertExists()
            val task = tasks.single { it.type == type }
            val progress = evaluated.progress.single { it.id == task.id }
            compose.onNode(hasText("Выполнено: ${progress.current} из ${progress.target}") and
                hasAnyAncestor(hasTestTag("level_task_${type.name.lowercase()}"))).assertExists()
            compose.onNodeWithTag("level_task_${type.name.lowercase()}").onChildren()
                .filter(hasText(if (task.id in evaluated.state.completedTaskIds) "Выполнено" else "В работе"))
                .assertCountEquals(1)
            assertTextFits()
        }
        compose.onNodeWithTag("game_tasks_summary").assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val minimum = 48f * compose.density.density - 1f
            assertTrue(node.size.toString(), node.size.width >= minimum && node.size.height >= minimum)
        }
        compose.onNodeWithText("Найдите 1 слово по теме «Животные»").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("level_riddle_1").onChildren().filter(hasText("Разгадана")).assertCountEquals(1)
        compose.onNodeWithTag("level_riddle_2").onChildren().filter(hasText("Ещё не разгадана")).assertCountEquals(1)
        compose.onNodeWithText("Слово из 4 букв, начинается на «К»").performScrollTo().assertIsDisplayed()
        // Ни полные ответы загадок, ни адреса их статей не попадают в дерево доступности.
        val semantics = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val text = semantics.flatMap { root -> flatten(root) }.flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text } +
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.joinToString("\n")
        assertFalse(text.contains("крот", ignoreCase = true))
        assertFalse(text.contains("карта", ignoreCase = true))
        assertFalse(text.contains("https://"))
        compose.onNodeWithText("кот").assertDoesNotExist()
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText("кот").performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("кот").performTouchInput { click() }
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        compose.runOnIdle { assertNull(state.value.dialog) }
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText("кот").assertDoesNotExist()
        compose.onNodeWithText("Статистика слов").performScrollTo().performClick()
        compose.onNodeWithTag("level_stats").assertExists()
        compose.onNodeWithText("Осталось").assertExists()
        compose.onNodeWithText("Взято шагов подсказок: 0 · потрачено единиц: 0").performScrollTo().assertIsDisplayed()
        assertTextFits()
    }

    @Test fun installedCampaignTasksMatchEveryLevelWithoutOpeningAnotherList() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = com.example.sostavslova.data.CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        fun ui(level: CampaignLevel): GameUiState {
            val game = GameState(level.word, emptyList(), emptyList())
            val evaluated = TaskRules.evaluate(CurrentCampaignLevel(level, game), campaign.countMinLength)
            return GameUiState(loading = false, game = game, campaignLevel = evaluated.state,
                taskProgress = evaluated.progress, totalLevels = campaign.levels.size)
        }
        val state = mutableStateOf(ui(campaign.levels.first()))
        compose.setContent { WordsTheme { GameScreen(state.value, {}, {}, {}, {}, {}, {}) } }
        for (level in campaign.levels) {
            compose.runOnIdle { state.value = ui(level) }
            compose.onNodeWithTag("game_tasks_summary").assertTextEquals("Задания · 0 из ${level.tasks.size}")
            TaskType.entries.forEach { type ->
                compose.onAllNodesWithTag("level_task_${type.name.lowercase()}")
                    .assertCountEquals(level.tasks.count { it.type == type })
            }
            compose.onNodeWithTag("game_tasks_toggle").assertDoesNotExist()
            compose.onNodeWithText("Задания").assertDoesNotExist()
        }
    }

    @Test fun accumulatedHintStepsCostAndZeroBudgetAreVisible() {
        val steps = listOf(HintStep(HintStage.THEME, "Животные"),
            HintStep(HintStage.DEFINITION, "Синтетическое безопасное описание", wiktionary = true),
            HintStep(HintStage.LENGTH, length = 4))
        val state = mutableStateOf(initial.copy(campaignHints = steps.take(2),
            campaignLevel = evaluated.state.copy(hints = HintState(ladder = HintLadder(
                target = HintTarget("крот"), steps = steps + HintStep(HintStage.FIRST_LETTER, "к"), nextStepIndex = 2))),
            hintCost = 0, hintRemaining = 0))
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, { clicks++ }, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("game_hint")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("game_hint_stage").performScrollTo().assertTextEquals("Подсказка · открыто 2 из 4 частей")
        compose.onNodeWithText("Животные").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Синтетическое безопасное описание").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("крот").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { state.value = state.value.copy(hintCost = 1, campaignHints = steps,
            campaignLevel = state.value.campaignLevel?.let { current -> current.copy(hints = current.hints.copy(
                ladder = current.hints.ladder.copy(nextStepIndex = 3))) }) }
        compose.onNodeWithText("Слово из 4 букв").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("game_hint_budget").performScrollTo()
            .assertTextContains("Каждые 4 найденных слова", substring = true)
        assertTextFits()
    }

    @Test fun hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange() {
        val masked = requireNotNull(DefinitionHint.prepare("крот", "Крот — условный обитатель подземного мира."))
        assertFalse(masked.contains("крот", ignoreCase = true))
        val steps = listOf(HintStep(HintStage.DEFINITION, masked),
            HintStep(HintStage.LENGTH, length = 4), HintStep(HintStage.FIRST_LETTER, "к"),
            HintStep(HintStage.PATTERN, "кр__"), HintStep(HintStage.ANSWER, "крот"))
        fun snapshot(open: Int, parts: List<HintStep> = steps, target: String = "крот") = initial.copy(
            campaignLevel = evaluated.state.copy(hints = HintState(ladder = HintLadder(
                target = HintTarget(target), steps = parts, nextStepIndex = open))),
            campaignHints = parts.take(open), hintCost = if (open == parts.size) null else 1, hintRemaining = 2)
        val state = mutableStateOf(snapshot(0))
        var clicks = 0
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, { clicks++ }, {}, {}, {})
                    }
                }
            }
        }
        for (open in listOf(0, 1, 3, 5)) {
            compose.runOnIdle { state.value = snapshot(open) }
            repeat(2) { pass ->
                if (pass == 1) restoration.emulateSavedInstanceStateRestore()
                compose.onNodeWithTag("game_hint_stage").performScrollTo()
                    .assertTextEquals("Подсказка · открыто $open из 5 частей")
                for (index in steps.indices) {
                    val part = compose.onAllNodesWithTag("game_hint_part_${index + 1}")
                    part.assertCountEquals(if (index < open) 1 else 0)
                    if (index < open) {
                        compose.onNodeWithTag("game_hint_part_${index + 1}").performScrollTo()
                            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Часть ${index + 1} из 5"))
                    }
                }
                // Check the unmerged tree too: closed content must not reach accessibility.
                steps.forEachIndexed { index, step ->
                    step.text?.let { text ->
                        compose.onAllNodesWithText(text, useUnmergedTree = true)
                            .assertCountEquals(if (index < open) 1 else 0)
                    }
                }
                val parts = compose.onAllNodes(hasTestTag("game_hint_part_1") or hasTestTag("game_hint_part_2") or
                    hasTestTag("game_hint_part_3") or hasTestTag("game_hint_part_4") or hasTestTag("game_hint_part_5"))
                    .fetchSemanticsNodes()
                assertEquals((1..open).map { "game_hint_part_$it" }, parts.map { it.config[SemanticsProperties.TestTag] })
                compose.onAllNodesWithTag("game_hint_button").assertCountEquals(1)
                val button = compose.onNodeWithTag("game_hint_button").performScrollTo()
                if (open == 5) {
                    button.assertIsNotEnabled()
                    compose.onNodeWithText("Все шаги этой подсказки уже показаны.").assertExists()
                } else {
                    button.assertIsEnabled().assertTextContains("Открыть следующую часть · цена 1 · осталось 2")
                }
                compose.onAllNodesWithText("Викисловарь", substring = true).assertCountEquals(0)
                assertTextFits()
            }
        }
        // A new target replaces the entire block, even if a stale auxiliary UI list remained.
        compose.runOnIdle { state.value = snapshot(1, listOf(HintStep(HintStage.LENGTH, length = 5)), "карта")
            .copy(campaignHints = steps) }
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка · открыто 1 из 1 частей")
        compose.onNodeWithTag("game_hint_part_1").assertTextContains("Слово из 5 букв")
        compose.onAllNodesWithText(masked, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("game_hint_part_2").assertCountEquals(0)
        compose.runOnIdle { state.value = snapshot(3, target = "кот") }
        // Once the target has been found, its old parts are no longer visible.
        compose.onAllNodesWithTag("game_hint_part_1").assertCountEquals(0)
        compose.onAllNodesWithText(masked, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { state.value = initial.copy(hintCost = null, hintRemaining = 2) }
        compose.onNodeWithText("Все слова уже найдены").assertExists()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = initial.copy(hintCost = 0, hintRemaining = 0) }
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка · откройте первую часть")
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { state.value = snapshot(1, steps.filter { it.stage != HintStage.DEFINITION })
            .copy(hintFallback = true, hintCost = 1, hintRemaining = 0) }
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка · открыто 1 из 4 частей")
        compose.onNodeWithText("Доступны подсказки по буквам.").assertExists()
        compose.onNodeWithTag("game_hint_part_1").assertTextContains("Слово из 4 букв")
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
        assertTextFits()
    }

    @Test fun tasksStayVisibleAcrossUpdatesAndLevelChangesWithoutClearingInput() {
        val state = mutableStateOf(initial)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("кот")
        compose.onNodeWithTag("level_task_t4").performScrollTo().assertExists()
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("кот")
        compose.runOnIdle { state.value = state.value.copy(message = GameMessage(com.example.sostavslova.R.string.correct_answer)) }
        compose.onNodeWithTag("level_task_t6").performScrollTo().assertExists()
        compose.onNodeWithTag("level_task_t7").performScrollTo().assertExists()
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("кот")
        compose.runOnIdle {
            state.value = state.value.copy(
                campaignLevel = evaluated.state.copy(level = evaluated.state.level.copy(number = 8,
                    word = "поздравление", tasks = listOf(CampaignTask.T1("count", 1)))),
                taskProgress = emptyList(), riddles = emptyMap())
        }
        compose.onNodeWithTag("level_task_t1").performScrollTo().assertExists()
        compose.onNodeWithTag("level_task_t4").assertDoesNotExist()
        compose.onNodeWithTag("game_riddle_preview").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("кот")
    }

    @Test fun longInputIsNotTruncatedAndKeepsAccessibleLabel() {
        val state = mutableStateOf(initial)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        val input = compose.onNode(hasSetTextAction())
        val long = "а".repeat(31)
        input.performScrollTo().performTextInput(long)
        input.assertTextEquals(long)
        input.assert(hasContentDescription("Ваш ответ:"))
        input.performImeAction()
        input.assertIsFocused()
    }

    @Test fun riddlesRemainUnderTaskAndReflectSolvedUpdates() {
        val riddles = listOf(
            RiddleUiState("Первая безопасная загадка", 3, "К", solved = true),
            RiddleUiState("Синтетическая смысловая загадка", 4, "К", wiktionary = true),
            RiddleUiState(null, 6, "К"))
        val state = mutableStateOf(initial.copy(riddles = mapOf("riddles" to riddles)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("game_riddle_preview").assertDoesNotExist()
        compose.onNodeWithText("Синтетическая смысловая загадка").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.").assertExists()
        compose.onNodeWithText("крот").assertDoesNotExist()
        compose.onNodeWithText("Первая безопасная загадка").assertExists()
        compose.onAllNodesWithText("Ещё не разгадана").assertCountEquals(2)
        compose.runOnIdle {
            state.value = state.value.copy(riddles = mapOf("riddles" to riddles.mapIndexed { index, riddle ->
                if (index == 1) riddle.copy(solved = true) else riddle
            }))
        }
        compose.onAllNodesWithText("Разгадана").assertCountEquals(2)
        compose.onNodeWithText("Загадка 3").assertExists()
        compose.onNodeWithText("Слово из 6 букв, начинается на «К»").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(riddles = mapOf("riddles" to riddles.map { it.copy(solved = true) }))
        }
        compose.onNodeWithTag("game_riddle_preview").assertDoesNotExist()
        compose.onAllNodesWithText("Разгадана").assertCountEquals(3)
        compose.onNodeWithTag("level_task_t4").assertExists()
    }

    @Test fun finaleSurvivesScreenRestorationAndBothActionsFit() {
        val summary = CampaignSummary(99, 100, 30, 30, 321, 12, 8)
        val state = mutableStateOf(initial.copy(finaleSummary = summary))
        val actions = mutableListOf<Boolean>()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, {}, {}, {}, {}, onCloseFinale = { toLevels ->
                            actions.add(toLevels)
                            state.value = state.value.copy(finaleSummary = null)
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag("level_finale_dialog").assertExists()
        compose.onNodeWithText("Игра пройдена").assertIsDisplayed()
        compose.onNodeWithText("Звёзды: 99 из 100").assertExists()
        compose.onNodeWithText("Найдено: 321 слово").assertExists()
        compose.onNodeWithText("Использовано шагов подсказок: 12").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("level_finale_dialog").assertExists()
        compose.onNodeWithText("Продолжить собирать звёзды").performScrollTo().assertIsDisplayed()
        assertTextFits()
        compose.onNodeWithText("Продолжить собирать звёзды").performClick()
        compose.onNodeWithTag("level_finale_dialog").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("level_finale_dialog").assertDoesNotExist()
        assertEquals(listOf(false), actions)
        compose.runOnIdle { state.value = state.value.copy(finaleSummary = summary) }
        compose.onNodeWithText("К уровням").performScrollTo().performClick()
        assertEquals(listOf(false, true), actions)
        compose.onNodeWithTag("level_finale_dialog").assertDoesNotExist()
    }

    private fun flatten(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun assertTextFits() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach { layout ->
                    assertFalse(layout.layoutInput.text.text, layout.hasVisualOverflow)
                }
            }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}, font={1}")
        fun sizes(): List<Array<Any>> = listOf(320, 360, 400).flatMap { width ->
            listOf(1f, 1.5f, 2f).map { scale -> arrayOf<Any>(width, scale) }
        } + listOf(arrayOf<Any>(840, 1f))
    }
}
