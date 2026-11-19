package com.example.sostavslova.ui.game

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CampaignGameUiTest {
    @get:Rule val compose = createComposeRule()
    // Собственный синтетический текст; статьи словарей в fixture не используются.
    private val maskedOwnDefinition = "Условный обитатель подземного мира для проверки экрана."
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
            RiddleUiState("Условное безопасное описание животного", solved = true),
            RiddleUiState("Вторая безопасная загадка"))),
    )

    @Test fun allSevenTasksRiddlesGroupsAndStatisticsAtNativeSize() {
        val state = mutableStateOf(initial)
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, { state.value = state.value.copy(dialog = it) },
                    { state.value = state.value.copy(dialog = null) }, {})
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
        compose.onNodeWithText("Вторая безопасная загадка").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("Использовано подсказок: 0").performScrollTo().assertIsDisplayed()
        assertTextFits()
    }

    @Test fun nextLevelOfferAndHintHaveSeparateAccessibleActions() {
        val state = mutableStateOf(initial.copy(totalLevels = 31, hintCost = 0, hintRemaining = 0))
        var transitions = 0
        var hints = 0
        compose.setContent {
            WordsTheme { GameScreen(state.value, {}, {}, { hints++ }, {}, {}, {},
                onNextLevel = { transitions++ }) }
        }
        compose.onNodeWithTag("game_next_level_button").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(nextLevelNumber = 31) }
        compose.onNodeWithTag("game_next_level").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("Следующий уровень открыт!").assertExists()
        compose.onNodeWithText("Вы можете перейти дальше или остаться здесь, чтобы отгадывать слова и собирать оставшиеся звёзды.")
            .assertExists()
        val button = compose.onNodeWithTag("game_next_level_button").performScrollTo()
        button.assertTextEquals("Перейти на уровень 31 →")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, transitions)
        compose.onNodeWithTag("game_hint").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка ещё не открыта")
        compose.onNodeWithTag("game_hint_length").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_pattern").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_remaining").assertTextEquals("Осталось подсказок: 0")
        compose.onNodeWithTag("game_hint_button").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertTextContains("Подсказка · бесплатно").performClick()
        assertEquals(1, hints)
        compose.runOnIdle { state.value = state.value.copy(navigatingNext = true) }
        compose.onNodeWithTag("game_next_level_button").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
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

    @Test fun accumulatedLetterStepsCostAndZeroBudgetAreVisible() {
        val steps = listOf(HintStep(HintStage.FIRST_LETTER, "к___", revealedPositions = setOf(0)),
            HintStep(HintStage.PATTERN, "кр__", revealedPositions = setOf(0, 1)),
            HintStep(HintStage.PATTERN, "кро_", revealedPositions = setOf(0, 1, 2)),
            HintStep(HintStage.ANSWER, "крот", revealedPositions = (0..3).toSet()))
        val state = mutableStateOf(initial.copy(
            campaignLevel = evaluated.state.copy(hints = HintState(ladder = HintLadder(
                target = HintTarget("крот"), steps = steps, letterLadderVersion = 1))),
            hintCost = 0, hintRemaining = 0))
        var clicks = 0
        compose.setContent { WordsTheme { GameScreen(state.value, {}, {}, { clicks++ }, {}, {}, {}) } }
        compose.onNodeWithTag("game_hint")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("game_hint_stage").performScrollTo().assertTextEquals("Подсказка ещё не открыта")
        compose.onNodeWithTag("game_hint_length").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_pattern").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_part_1").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsEnabled()
            .assertTextContains("Подсказка · бесплатно").performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { state.value = state.value.copy(hintCost = 1,
            campaignLevel = state.value.campaignLevel?.let { current -> current.copy(hints = current.hints.copy(
                ladder = current.hints.ladder.copy(nextStepIndex = 1, revealedPositions = setOf(0)))) }) }
        compose.onNodeWithTag("game_hint_stage").performScrollTo().assertTextEquals("Подсказка · открыто 1 из 4")
        compose.onNodeWithTag("game_hint_length").assertTextEquals("Слово из 4 букв")
        compose.onNodeWithTag("game_hint_pattern")
            .assertContentDescriptionEquals("Слово из 4 букв. Открыто: 1-я К.")
        compose.onNodeWithTag("game_hint_part_1").assertDoesNotExist()
        repeat(4) { index -> compose.onNodeWithTag("game_hint_slot_${index + 1}", useUnmergedTree = true).assertExists() }
        assertSlot(1, 'К')
        assertSlot(2, null)
        assertSlot(3, null)
        assertSlot(4, null)
        compose.onAllNodesWithText("_", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("к___", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("кр__", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("кро_", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("крот", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
            .assertTextContains("Подсказка (−1, останется 0)")
        compose.onNodeWithTag("game_hint_remaining").assertTextEquals("Осталось подсказок: 0")
        compose.onNodeWithTag("game_hint_budget").performScrollTo()
            .assertTextContains("Каждые 4 найденных слова", substring = true)
        assertTextFits()
    }

    @Test fun preOpenedEndingCountsAsOpenedInLetterSlots() {
        val steps = listOf(
            HintStep(HintStage.PATTERN, "б__ет", revealedPositions = setOf(0, 3, 4)),
            HintStep(HintStage.PATTERN, "би_ет", revealedPositions = setOf(0, 1, 3, 4)),
            HintStep(HintStage.ANSWER, "билет", revealedPositions = (0..4).toSet()),
        )
        val state = mutableStateOf(initial.copy(
            campaignLevel = evaluated.state.copy(hints = HintState(ladder = HintLadder(
                target = HintTarget("билет"), steps = steps, nextStepIndex = 1, letterLadderVersion = 1))),
            hintCost = 1, hintRemaining = 2))
        compose.setContent { WordsTheme { GameScreen(state.value, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("game_hint_stage").performScrollTo().assertTextEquals("Подсказка · открыто 3 из 5")
        compose.onNodeWithTag("game_hint_length").assertTextEquals("Слово из 5 букв")
        compose.onNodeWithTag("game_hint_pattern")
            .assertContentDescriptionEquals("Слово из 5 букв. Открыто: 1-я Б, 4-я Е, 5-я Т.")
        assertSlot(1, 'Б')
        assertSlot(2, null)
        assertSlot(3, null)
        assertSlot(4, 'Е')
        assertSlot(5, 'Т')
        assertTextFits()
    }

    @Test fun hintShowsOnlyLatestStepAndClearsAfterRestorationOrTargetChange() {
        val steps = listOf(HintStep(HintStage.FIRST_LETTER, "к___", revealedPositions = setOf(0)),
            HintStep(HintStage.PATTERN, "кр__", revealedPositions = setOf(0, 1)),
            HintStep(HintStage.PATTERN, "кро_", revealedPositions = setOf(0, 1, 2)),
            HintStep(HintStage.ANSWER, "крот", revealedPositions = (0..3).toSet()))
        fun snapshot(open: Int, parts: List<HintStep> = steps, target: String = "крот") = initial.copy(
            campaignLevel = evaluated.state.copy(hints = HintState(ladder = HintLadder(
                target = HintTarget(target), steps = parts, nextStepIndex = open, letterLadderVersion = 1))),
            campaignHints = parts.take(open), hintCost = if (open == parts.size) null else 1, hintRemaining = 2)
        val state = mutableStateOf(snapshot(0))
        var clicks = 0
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, { clicks++ }, {}, {}, {})
            }
        }
        for (open in listOf(0, 1, 3, 4)) {
            compose.runOnIdle { state.value = snapshot(open) }
            repeat(2) { pass ->
                if (pass == 1) restoration.emulateSavedInstanceStateRestore()
                compose.onNodeWithTag("game_hint_stage").performScrollTo()
                    .assertTextEquals(if (open == 0) "Подсказка ещё не открыта"
                        else "Подсказка · открыто $open из 4")
                if (open == 0) {
                    compose.onNodeWithTag("game_hint_length").assertDoesNotExist()
                    compose.onNodeWithTag("game_hint_pattern").assertDoesNotExist()
                } else {
                    val pattern = requireNotNull(steps[open - 1].text)
                    compose.onNodeWithTag("game_hint_length").assertTextEquals("Слово из 4 букв")
                    compose.onNodeWithTag("game_hint_pattern").assertExists()
                    repeat(4) { index ->
                        compose.onNodeWithTag("game_hint_slot_${index + 1}", useUnmergedTree = true).assertExists()
                        assertSlot(index + 1, pattern[index].takeUnless { it == '_' }?.uppercaseChar())
                    }
                    compose.onAllNodesWithText("_", useUnmergedTree = true).assertCountEquals(0)
                }
                // Check the unmerged tree too: no previous full pattern reaches accessibility.
                steps.forEach { step ->
                    step.text?.let { text ->
                        compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
                    }
                }
                compose.onAllNodes(hasTestTag("game_hint_part_1") or hasTestTag("game_hint_part_2") or
                    hasTestTag("game_hint_part_3") or hasTestTag("game_hint_part_4") or hasTestTag("game_hint_part_5"))
                    .assertCountEquals(0)
                compose.onAllNodesWithTag("game_hint_button").assertCountEquals(1)
                val button = compose.onNodeWithTag("game_hint_button").performScrollTo()
                if (open == 4) {
                    button.assertIsNotEnabled()
                    compose.onNodeWithText("Слово показано целиком. Введите его, чтобы получить новую подсказку.")
                        .assertExists()
                } else {
                    button.assertIsEnabled().assertTextContains("Подсказка (−1, останется 2)")
                }
                compose.onAllNodesWithText("Викисловарь", substring = true).assertCountEquals(0)
                assertTextFits()
            }
        }
        // A new target replaces the entire block, even if a stale auxiliary UI list remained.
        val kartaSteps = listOf(HintStep(HintStage.FIRST_LETTER, "к____", revealedPositions = setOf(0)),
            HintStep(HintStage.PATTERN, "ка___", revealedPositions = setOf(0, 1)),
            HintStep(HintStage.PATTERN, "кар__", revealedPositions = setOf(0, 1, 2)),
            HintStep(HintStage.PATTERN, "карт_", revealedPositions = setOf(0, 1, 2, 3)),
            HintStep(HintStage.ANSWER, "карта", revealedPositions = (0..4).toSet()))
        compose.runOnIdle { state.value = initial.copy(
            campaignLevel = evaluated.state.copy(hints = HintState(ladder = HintLadder(
                target = HintTarget("карта"), steps = kartaSteps, nextStepIndex = 1, letterLadderVersion = 1))),
            campaignHints = steps, hintCost = 1, hintRemaining = 2) }
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка · открыто 1 из 5")
        compose.onNodeWithTag("game_hint_length").assertTextEquals("Слово из 5 букв")
        assertSlot(1, 'К')
        assertSlot(2, null)
        compose.onAllNodesWithText("кр__", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("game_hint_part_2").assertCountEquals(0)
        compose.runOnIdle { state.value = snapshot(3, target = "кот") }
        // Once the target has been found, its old pattern is no longer visible.
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка ещё не открыта")
        compose.onNodeWithTag("game_hint_pattern").assertDoesNotExist()
        compose.onNodeWithTag("game_hint_part_1").assertDoesNotExist()
        compose.onAllNodesWithText("кр__", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { state.value = initial.copy(hintCost = null, hintRemaining = 2) }
        compose.onNodeWithText("Все слова уже найдены").assertExists()
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
            .assertTextContains("Подсказка · недоступно")
        compose.runOnIdle { state.value = initial.copy(hintCost = 0, hintRemaining = 0) }
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка ещё не открыта")
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsEnabled()
            .assertTextContains("Подсказка · бесплатно").performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { state.value = snapshot(1).copy(hintCost = 1, hintRemaining = 0) }
        compose.onNodeWithTag("game_hint_stage").assertTextEquals("Подсказка · открыто 1 из 4")
        compose.onNodeWithTag("game_hint_length").assertTextEquals("Слово из 4 букв")
        assertSlot(1, 'К')
        compose.onNodeWithTag("game_hint_button").performScrollTo().assertIsNotEnabled()
            .assertTextContains("Подсказка (−1, останется 0)")
        assertTextFits()
    }

    private fun assertSlot(index: Int, letter: Char?) {
        val slot = compose.onNodeWithTag("game_hint_slot_$index", useUnmergedTree = true)
        slot.assertExists()
        if (letter == null) {
            slot.onChildren().assertCountEquals(0)
        } else {
            slot.onChildren().filter(hasText(letter.uppercaseChar().toString())).assertCountEquals(1)
        }
    }

    @Test fun tasksStayVisibleAcrossUpdatesAndLevelChangesWithoutClearingInput() {
        val state = mutableStateOf(initial)
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {}, {})
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
            WordsTheme {
                GameScreen(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {}, {})
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
            RiddleUiState("Первая безопасная загадка", solved = true),
            RiddleUiState(maskedOwnDefinition),
            RiddleUiState("Третья безопасная загадка"))
        val state = mutableStateOf(initial.copy(riddles = mapOf("riddles" to riddles)))
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("game_riddle_preview").assertDoesNotExist()
        compose.onNodeWithText(maskedOwnDefinition).performScrollTo().assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("level_task_t4")))
        assertOwnHintProvenanceAndNoAnswer()
        compose.onNodeWithText("Первая безопасная загадка").assertExists()
        compose.onAllNodesWithText("Ещё не разгадана").assertCountEquals(2)
        compose.runOnIdle {
            state.value = state.value.copy(riddles = mapOf("riddles" to riddles.mapIndexed { index, riddle ->
                if (index == 1) riddle.copy(solved = true) else riddle
            }))
        }
        compose.onAllNodesWithText("Разгадана").assertCountEquals(2)
        compose.onAllNodesWithText("Ещё не разгадана").assertCountEquals(1)
        compose.onNodeWithTag("level_riddle_2").onChildren().filter(hasText("Разгадана")).assertCountEquals(1)
        assertOwnHintProvenanceAndNoAnswer()
        compose.onNodeWithText("Загадка 3").assertExists()
        compose.onNodeWithText("Третья безопасная загадка").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(riddles = mapOf("riddles" to riddles.map { it.copy(solved = true) }))
        }
        compose.onNodeWithTag("game_riddle_preview").assertDoesNotExist()
        compose.onAllNodesWithText("Разгадана").assertCountEquals(3)
        compose.onAllNodesWithText("Ещё не разгадана").assertCountEquals(0)
        compose.onNodeWithText(maskedOwnDefinition).assert(hasAnyAncestor(hasTestTag("level_task_t4")))
        assertOwnHintProvenanceAndNoAnswer()
        compose.onNodeWithTag("level_task_t4").assertExists()
    }

    private fun assertOwnHintProvenanceAndNoAnswer() {
        val text = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap(::flatten).flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text } +
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.joinToString("\n")
        assertFalse(text.contains("крот", ignoreCase = true))
        assertFalse(text.contains("Викисловарь", ignoreCase = true))
        assertFalse(text.contains("CC BY-SA", ignoreCase = true))
        assertFalse(text.contains("https://"))
    }

    @Test fun finaleSurvivesScreenRestorationAndBothActionsFit() {
        val summary = CampaignSummary(99, 100, 30, 30, 321, 12, 8)
        val state = mutableStateOf(initial.copy(finaleSummary = summary))
        val actions = mutableListOf<Boolean>()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, {}, {}, {}, onCloseFinale = { toLevels ->
                    actions.add(toLevels)
                    state.value = state.value.copy(finaleSummary = null)
                })
            }
        }
        compose.onNodeWithTag("level_finale_dialog").assertExists()
        compose.onNodeWithText("Игра пройдена").assertIsDisplayed()
        compose.onNodeWithText("Звёзды: 99 из 100").assertExists()
        compose.onNodeWithText("Найдено: 321 слово").assertExists()
        compose.onNodeWithText("Использовано подсказок: 12").assertExists()
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
                    assertTextLayoutFits(layout, "native, " +
                        "tag=${node.config.getOrNull(SemanticsProperties.TestTag)}")
                }
            }
    }

}
