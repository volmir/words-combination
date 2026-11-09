package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskRulesTest {
    private val source = "электростанция"
    private val index = WordIndex(listOf("кот", "крот", "торт", "рост", "рот", "ток", "нота", "елка", "колея"))

    private fun start(vararg tasks: CampaignTask, word: String = source): CurrentCampaignLevel =
        CurrentCampaignLevel(
            CampaignLevel(1, word, 1, 1, tasks.toList()),
            GameState.start(word, index),
        )

    private fun accept(current: CurrentCampaignLevel, word: String, countMinLength: Int = 4): TaskEvaluation {
        val submission = TaskRules.submit(current, word, countMinLength)
        assertEquals(AnswerResult.CORRECT, submission.result)
        return submission.evaluation
    }

    private fun TaskEvaluation.task(id: String) = progress.single { it.id == id }

    @Test fun threeLetterAnswerIsFoundAndCountedInStatisticsButOnlyAdvancesEligibleTasks() {
        val result = accept(start(
            CampaignTask.T1("count", 1),
            CampaignTask.T2("initial", 'к', 1),
            CampaignTask.T6("theme", "animals", 1, listOf("кот", "крот")),
            CampaignTask.T7("letter", 'о', 1),
        ), " КОТ ")

        assertEquals(listOf("кот"), result.state.game.found)
        assertEquals(1, result.state.game.foundCount)
        assertEquals(1, result.state.game.statistics().totalFound)
        assertEquals(1, result.state.game.statistics().rows.single { it.length == 3 }.found)
        assertEquals(TaskProgress("count", 0, 1, false), result.task("count"))
        assertEquals(TaskProgress("initial", 0, 1, false), result.task("initial"))
        assertEquals(setOf("theme", "letter"), result.state.completedTaskIds)
        assertEquals(setOf("theme", "letter"), result.newlyCompletedTaskIds)
        assertEquals(2, result.stars)
    }

    @Test fun countingTasksUseConfiguredMinimumAndInitialLetterOnly() {
        var current = start(CampaignTask.T1("count", 2), CampaignTask.T2("initial", 'т', 2))
        current = accept(current, "кот").state
        val first = accept(current, "торт")
        assertEquals(TaskProgress("count", 1, 2, false), first.task("count"))
        assertEquals(TaskProgress("initial", 1, 2, false), first.task("initial"))
        val second = accept(first.state, "крот")
        assertEquals(TaskProgress("count", 2, 2, true), second.task("count"))
        assertEquals(TaskProgress("initial", 1, 2, false), second.task("initial"))

        val configured = TaskRules.evaluate(second.state.copy(completedTaskIds = emptySet()), 5)
        assertTrue(configured.progress.all { it.current == 0 && !it.completed })
        val longer = accept(configured.state, "колея", 5)
        assertEquals(1, longer.task("count").current)
        val initial = accept(start(CampaignTask.T2("initial", 'к', 1)), "колея", 5)
        assertEquals(TaskProgress("initial", 1, 1, true), initial.task("initial"))
    }

    @Test fun longWordTaskAcceptsAnyAnswerAtLeastAsLongAsRequired() {
        val current = start(CampaignTask.T3("length", 4, "торт"))
        val short = accept(current, "кот")
        assertEquals(TaskProgress("length", 0, 1, false), short.task("length"))
        val alternative = accept(short.state, "крот")
        assertEquals(TaskProgress("length", 1, 1, true), alternative.task("length"))
        val longer = accept(current, "колея")
        assertEquals(setOf("length"), longer.newlyCompletedTaskIds)
        val assigned = accept(alternative.state, "торт")
        assertEquals(1, assigned.stars)
        assertTrue(assigned.newlyCompletedTaskIds.isEmpty())
    }

    @Test fun threeRiddlesRequireAllAssignedAnswersAndAwardOneStar() {
        val current = start(CampaignTask.T4("riddles", listOf("кот", "крот", "торт")))
        val unrelated = accept(current, "рост")
        assertEquals(TaskProgress("riddles", 0, 3, false), unrelated.task("riddles"))
        val first = accept(unrelated.state, "кот")
        val second = accept(first.state, "крот")
        assertEquals(TaskProgress("riddles", 2, 3, false), second.task("riddles"))
        assertEquals(0, second.stars)
        assertTrue(second.newlyCompletedTaskIds.isEmpty())
        val third = accept(second.state, "торт")
        assertEquals(TaskProgress("riddles", 3, 3, true), third.task("riddles"))
        assertEquals(setOf("riddles"), third.newlyCompletedTaskIds)
        assertEquals(1, third.stars)
    }

    @Test fun endingTaskRequiresBothSuffixAndMinimumLength() {
        val current = start(CampaignTask.T5("ending", "от", 4))
        val short = accept(current, "кот")
        val wrongEnding = accept(short.state, "торт")
        assertEquals(TaskProgress("ending", 0, 1, false), wrongEnding.task("ending"))
        val matched = accept(wrongEnding.state, "крот")
        assertEquals(TaskProgress("ending", 1, 1, true), matched.task("ending"))
        val threeLettersAllowed = accept(start(CampaignTask.T5("ending", "от", 3)), "кот")
        assertEquals(1, threeLettersAllowed.stars)
    }

    @Test fun themeUsesOnlyItsLocalSetAndAllowsThreeLetterAnswers() {
        val current = start(CampaignTask.T6("theme", "animals", 2, listOf("кот", "крот")))
        val unrelated = accept(current, "торт")
        assertEquals(0, unrelated.task("theme").current)
        val first = accept(unrelated.state, "кот")
        assertEquals(TaskProgress("theme", 1, 2, false), first.task("theme"))
        val second = accept(first.state, "крот")
        assertEquals(TaskProgress("theme", 2, 2, true), second.task("theme"))
    }

    @Test fun containedLetterCountsWordsNotOccurrencesOrOnlyInitials() {
        val current = start(CampaignTask.T7("letter", 'т', 2))
        val absent = accept(current, "елка")
        assertEquals(0, absent.task("letter").current)
        val twiceInOneWord = accept(absent.state, "торт")
        assertEquals(TaskProgress("letter", 1, 2, false), twiceInOneWord.task("letter"))
        val inside = accept(twiceInOneWord.state, "кот")
        assertEquals(TaskProgress("letter", 2, 2, true), inside.task("letter"))
    }

    @Test fun oneAnswerCanCompleteSeveralTasksTogether() {
        val result = accept(start(
            CampaignTask.T1("count", 1),
            CampaignTask.T2("initial", 'к', 1),
            CampaignTask.T3("length", 4, "торт"),
            CampaignTask.T4("riddle", listOf("крот")),
            CampaignTask.T5("ending", "от", 4),
        ), "крот")
        assertEquals(setOf("count", "initial", "length", "riddle", "ending"), result.newlyCompletedTaskIds)
        assertTrue(result.progress.all { it.completed })
        assertEquals(5, result.stars)
    }

    @Test fun repeatedAnswersAndRepeatedEvaluationNeverAwardAnotherStar() {
        val first = accept(start(CampaignTask.T1("count", 1)), "крот")
        val repeat = TaskRules.submit(first.state, " КРОТ ", 4)
        assertEquals(AnswerResult.ALREADY_FOUND, repeat.result)
        assertEquals(first.state, repeat.evaluation.state)
        assertTrue(repeat.evaluation.newlyCompletedTaskIds.isEmpty())
        val evaluated = TaskRules.evaluate(repeat.evaluation.state, 4)
        assertTrue(evaluated.newlyCompletedTaskIds.isEmpty())
        val another = accept(evaluated.state, "торт")
        assertEquals(TaskProgress("count", 1, 1, true), another.task("count"))
        assertEquals(1, another.stars)
        assertTrue(another.newlyCompletedTaskIds.isEmpty())
    }

    @Test fun yoAndYeAreOneAnswerAcrossAllPredicates() {
        val current = start(
            CampaignTask.T2("initial", 'е', 2),
            CampaignTask.T4("riddle", listOf("елка")),
            CampaignTask.T5("ending", "елка", 4),
            CampaignTask.T6("theme", "plants", 1, listOf("елка")),
            CampaignTask.T7("letter", 'е', 2),
        )
        val first = accept(current, " ЁЛКА ")
        assertEquals(listOf("елка"), first.state.game.found)
        assertEquals(setOf("riddle", "ending", "theme"), first.newlyCompletedTaskIds)
        val duplicate = TaskRules.submit(first.state, "елка", 4)
        assertEquals(AnswerResult.ALREADY_FOUND, duplicate.result)
        assertTrue(duplicate.evaluation.newlyCompletedTaskIds.isEmpty())

        val restored = TaskRules.evaluate(current.copy(game = current.game.copy(
            found = listOf("ёлка", "елка", " ЁЛКА "),
        )), 4)
        assertEquals(first.progress, restored.progress)
        assertEquals(first.state.completedTaskIds, restored.state.completedTaskIds)
    }

    @Test fun sourceAndRejectedInputsDoNotProgressTasks() {
        val current = start(CampaignTask.T1("count", 1), CampaignTask.T7("letter", 'т', 1))
        for ((raw, expected) in listOf(
            source.uppercase() to AnswerResult.SAME_AS_SOURCE,
            "слон" to AnswerResult.NOT_FOUND,
            "коттт" to AnswerResult.NOT_FOUND,
            "то" to AnswerResult.TOO_SHORT,
            "123" to AnswerResult.EMPTY,
        )) {
            val result = TaskRules.submit(current, raw, 4)
            assertEquals(expected, result.result)
            assertEquals(current, result.evaluation.state)
            assertTrue(result.evaluation.progress.all { it.current == 0 && !it.completed })
            assertTrue(result.evaluation.newlyCompletedTaskIds.isEmpty())
        }
    }

    @Test fun earnedStarsSurviveRestorationAndMergeWithNewlyCompletedTasks() {
        val current = start(CampaignTask.T1("count", 2), CampaignTask.T7("letter", 'т', 1))
            .copy(completedTaskIds = setOf("count"))
        val restored = TaskRules.evaluate(current, 4)
        assertEquals(TaskProgress("count", 0, 2, true), restored.task("count"))
        assertFalse(restored.task("letter").completed)
        assertEquals(1, restored.stars)
        assertTrue(restored.newlyCompletedTaskIds.isEmpty())
        val next = accept(restored.state, "кот")
        assertEquals(setOf("count", "letter"), next.state.completedTaskIds)
        assertEquals(setOf("letter"), next.newlyCompletedTaskIds)
        assertEquals(2, next.stars)
    }

    @Test fun restoredFoundCanRecoverMissingStarExactlyOnce() {
        val current = start(CampaignTask.T4("riddle", listOf("кот")))
        val restored = current.copy(game = GameState.start(source, index, listOf("кот")))
        val recovered = TaskRules.evaluate(restored, 4)
        assertEquals(setOf("riddle"), recovered.newlyCompletedTaskIds)
        assertEquals(1, recovered.stars)
        val again = TaskRules.evaluate(recovered.state, 4)
        assertEquals(1, again.stars)
        assertTrue(again.newlyCompletedTaskIds.isEmpty())
    }

    @Test fun answerFoundOnAnotherLevelIsAcceptedAndAdvancesItsOwnTasks() {
        val first = accept(start(CampaignTask.T7("first", 'т', 1)), "кот")
        val second = accept(start(CampaignTask.T7("second", 'т', 1), word = "картотека"), "кот")
        assertEquals(listOf("кот"), first.state.game.found)
        assertEquals(listOf("кот"), second.state.game.found)
        assertEquals(setOf("first"), first.state.completedTaskIds)
        assertEquals(setOf("second"), second.newlyCompletedTaskIds)
    }
}
