package com.example.sostavslova.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HintPanelModelTest {
    private val tasks = listOf(CampaignTask.T1("count", 8),
        CampaignTask.T4("riddle", listOf("крот")), CampaignTask.T2("initial", 'т', 8))
    private fun start(tasks: List<CampaignTask> = this.tasks) = CurrentCampaignLevel(
        CampaignLevel(1, "электростанция", 1, 1, tasks),
        GameState.start("электростанция", WordIndex(listOf("крот", "торт", "рост", "кот"))))
    private fun panel(current: CurrentCampaignLevel) = HintPanelModel.of(current,
        HintRules.nextCost(current), HintRules.budget(current).remaining, 4)
    private fun next(current: CurrentCampaignLevel) = HintRules.next(current, random = Random(17)).state

    @Test fun beforeFirstHintHasAutoAndEligibleTasksButNoPatternOrLeakedLength() {
        val current = start()
        val model = panel(current)
        assertEquals(HintPanelLabel.AUTO, model.target.label)
        assertNull(model.target.taskId)
        assertEquals(listOf(null, "count", "riddle", "initial"), model.options.map { it.taskId })
        assertNull(model.selectedTaskId)
        assertNull(model.pattern)
        assertEquals(0, model.wordLength)
        assertEquals(0, model.openedLetters)
        assertFalse(model.selectionLocked)
        assertFalse(model.ladderComplete)
        assertEquals(0, model.cost)
        assertEquals(5, model.remaining)
    }

    @Test fun firstFreeHintHasTargetCaptionAndLeavesSelectionAvailable() {
        val current = next(start())
        val model = panel(current)
        assertEquals("riddle", model.target.taskId)
        assertEquals(HintPanelLabel.RIDDLE_NUMBER, model.target.label)
        assertEquals(1, model.target.number)
        assertEquals("к___", model.pattern)
        assertEquals(4, model.wordLength)
        assertEquals(1, model.openedLetters)
        assertFalse(model.selectionLocked)
        assertEquals(1, model.cost)
        assertEquals(5, model.remaining)
    }

    @Test fun paidStepLocksChoiceAndUsesRemainingBudget() {
        val current = next(next(start()))
        val model = panel(current)
        assertTrue(model.selectionLocked)
        assertEquals(2, model.openedLetters)
        assertEquals(1, model.cost)
        assertEquals(4, model.remaining)
        assertEquals(current, HintRules.selectTask(current, "initial"))
    }

    @Test fun completedLadderStaysLockedUntilTargetIsFound() {
        var current = start()
        repeat(4) { current = next(current) }
        val model = panel(current)
        assertTrue(model.ladderComplete)
        assertTrue(model.selectionLocked)
        assertEquals("крот", model.pattern)
        assertEquals(4, model.openedLetters)
        assertNull(model.cost)
        val found = panel(current.copy(game = current.game.copy(found = listOf("крот"))))
        assertFalse(found.selectionLocked)
        assertFalse(found.ladderComplete)
        assertNull(found.pattern)
        assertEquals(HintPanelLabel.AUTO, found.target.label)
    }

    @Test fun singleTaskAndNoEligibleTasksHaveNoChoices() {
        assertTrue(panel(start(listOf(tasks.first()))).options.isEmpty())
        val current = start().let { it.copy(game = it.game.copy(found = it.game.allWords)) }
        assertTrue(panel(current).options.isEmpty())
        assertNull(panel(current).cost)
    }

    @Test fun unavailablePreferenceFallsBackToAutoAndDoesNotSelectIneligibleTasks() {
        val current = start().copy(hints = HintState(preferredTaskId = "missing"))
        assertNull(panel(current).selectedTaskId)
        assertEquals(HintPanelLabel.AUTO, panel(current).target.label)
        val noWords = start(listOf(tasks.first(), CampaignTask.T2("empty", 'я', 1)))
            .copy(hints = HintState(preferredTaskId = "empty"))
        assertEquals(listOf(null, "count"), panel(noWords).options.map { it.taskId })
        assertNull(panel(noWords).selectedTaskId)
        val completed = start(listOf(CampaignTask.T1("done", 1), tasks[1]))
            .let { it.copy(game = it.game.copy(found = listOf("торт")),
                hints = HintState(preferredTaskId = "done")) }
        assertEquals(listOf(null, "riddle"), panel(completed).options.map { it.taskId })
        assertNull(panel(completed).selectedTaskId)
    }

    @Test fun validPreferenceAndPaidPriceUseCoreParameters() {
        val current = HintRules.selectTask(start(), "initial")
        val model = panel(current)
        assertEquals("initial", model.selectedTaskId)
        assertEquals("initial", model.target.taskId)
        assertEquals(HintPanelLabel.START_LETTER, model.target.label)
        assertEquals("Т", model.target.argument)
        assertEquals(1, model.cost)
        assertEquals(5, model.remaining)
        val custom = HintPanelModel.of(start(), 1, 3, 5)
        assertEquals(1, custom.cost)
        assertEquals(3, custom.remaining)
        assertTrue(custom.options.none { it.taskId == "count" || it.taskId == "initial" })
    }

    @Test fun labelsCarryParametersWithoutComposeOrAnswerText() {
        val allTasks = listOf(CampaignTask.T1("t1", 4), CampaignTask.T2("t2", 'к', 2),
            CampaignTask.T3("t3", 4, "крот"), CampaignTask.T4("t4", listOf("крот", "торт")),
            CampaignTask.T5("t5", "от", 4), CampaignTask.T6("t6", "objects", 2, listOf("крот")),
            CampaignTask.T7("t7", 'р', 2))
        val options = panel(start(allTasks)).options.drop(1)
        assertEquals(listOf(HintPanelLabel.MIN_LENGTH, HintPanelLabel.START_LETTER,
            HintPanelLabel.MIN_LENGTH, HintPanelLabel.RIDDLES, HintPanelLabel.ENDING,
            HintPanelLabel.CATEGORY, HintPanelLabel.CONTAINS_LETTER), options.map { it.label })
        assertEquals(4, options[0].number)
        assertEquals(4, options[2].number)
        assertEquals("от", options[4].argument)
        assertEquals("objects", options[5].argument)
        assertEquals("Р", options[6].argument)
    }

    @Test fun restoresLegacyLettersAndHidesFoundTargetsWithoutMutatingInput() {
        val current = start().copy(hints = HintState(HintLadder(
            HintTarget("крот", "riddle", TaskType.T4),
            listOf(HintStep(HintStage.DEFINITION, "Синтетический текст"),
                HintStep(HintStage.FIRST_LETTER, "к", revealedPositions = setOf(0))),
            nextStepIndex = 2)))
        val model = panel(current)
        assertEquals("к___", model.pattern)
        assertEquals(1, model.openedLetters)
        assertEquals(HintStage.DEFINITION, current.hints.ladder.steps.first().stage)
        assertNull(panel(current.copy(game = current.game.copy(found = listOf("крот")))).pattern)
    }
}
