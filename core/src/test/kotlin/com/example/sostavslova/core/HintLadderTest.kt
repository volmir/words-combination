package com.example.sostavslova.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HintLadderTest {
    private val task = CampaignTask.T4("riddle", listOf("билет"))
    private val target = HintTarget("билет", task.id, TaskType.T4)

    @Test fun eachPurchaseAddsOnePositionAndLastPurchaseIsAnswer() {
        for (seed in 0..20) {
            val ladder = HintLadder.build(target, task, HintWordData(), Random(seed))
            assertEquals(5, ladder.steps.size)
            assertTrue(ladder.shown.isEmpty())
            assertTrue(ladder.revealedPositions.isEmpty())
            assertEquals("б____", ladder.steps.first().text)
            var positions = emptySet<Int>()
            ladder.steps.forEachIndexed { index, step ->
                assertEquals(1, (step.revealedPositions - positions).size)
                positions = step.revealedPositions
                assertEquals(index + 1, step.text?.count { it != '_' })
                assertEquals(5, step.text?.length)
            }
            assertEquals(HintStage.ANSWER, ladder.steps.last().stage)
            assertEquals("билет", ladder.steps.last().text)
        }
    }

    @Test fun knownRiddleInitialIsNotASeparateStepAndOnlyFirstNewPositionIsFree() {
        val current = CurrentCampaignLevel(CampaignLevel(1, "билетик", 1, 1, listOf(task)),
            GameState.start("билетик", WordIndex(listOf("билет"))))
        val first = HintRules.next(current, mapOf("билет" to HintWordData(knownPositions = setOf(0))), random = Random(7))
        val step = assertNotNull(first.step)
        assertEquals(HintStage.PATTERN, step.stage)
        assertEquals(2, step.revealedPositions.size)
        assertTrue(0 in step.revealedPositions)
        assertEquals(4, first.state.hints.ladder.steps.size)
        assertEquals(0, first.state.hints.paidUnits)
        val second = HintRules.next(first.state)
        assertEquals(1, second.state.hints.paidUnits)
        assertEquals(3, second.step?.revealedPositions?.size)
    }

    @Test fun migrationKeepsAcquiredPatternsAndUnpurchasedOrderIncludingRepeatedLetters() {
        val old = HintLadder(HintTarget("торт", "riddle", TaskType.T4), listOf(
            HintStep(HintStage.LENGTH, length = 4),
            HintStep(HintStage.FIRST_LETTER, "т", revealedPositions = setOf(0)),
            HintStep(HintStage.PATTERN, "т__т", revealedPositions = setOf(0, 3)),
            HintStep(HintStage.ANSWER, "торт", revealedPositions = (0..3).toSet())),
            nextStepIndex = 2, revealedPositions = setOf(0))
        val migrated = old.lettersOnly(null)
        assertEquals(listOf("т___"), migrated.shown.map { it.text })
        assertEquals("т__т", migrated.steps[1].text)
        assertEquals(setOf(0), migrated.revealedPositions)
        assertEquals(4, migrated.steps.size)
        assertEquals(migrated, migrated.lettersOnly(null))
        assertEquals(migrated, old.lettersOnly(null))
    }

    @Test fun oldPaidFirstLetterRemainsAcquiredWhenRiddleFallbackAlsoShowsIt() {
        val old = HintLadder(target, listOf(
            HintStep(HintStage.DEFINITION, "Синтетическое условие"),
            HintStep(HintStage.FIRST_LETTER, "б", revealedPositions = setOf(0)),
            HintStep(HintStage.ANSWER, "билет")), nextStepIndex = 2, revealedPositions = setOf(0))
        val migrated = old.lettersOnly(task, setOf(0))
        assertEquals(1, migrated.nextStepIndex)
        assertEquals("б____", migrated.shown.single().text)
        assertEquals(old.lettersOnly(task), migrated)
    }

    @Test fun migrationUsesKnownTaskSuffixWithoutSpendingOrRevealingAnotherPosition() {
        val ending = CampaignTask.T5("ending", "ет", 4)
        val old = HintLadder(target.copy(taskId = ending.id, taskType = ending.type), listOf(
            HintStep(HintStage.LENGTH, length = 5),
            HintStep(HintStage.FIRST_LETTER, "б", revealedPositions = setOf(0, 3, 4)),
            HintStep(HintStage.ANSWER, "билет")), nextStepIndex = 1, revealedPositions = setOf(3, 4))
        val migrated = old.lettersOnly(ending)
        assertTrue(migrated.shown.isEmpty())
        assertEquals(setOf(3, 4), migrated.revealedPositions)
        assertEquals(3, migrated.steps.size)
        assertEquals("б__ет", migrated.steps.first().text)
    }
}
