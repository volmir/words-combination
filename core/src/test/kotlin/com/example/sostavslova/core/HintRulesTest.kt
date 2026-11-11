package com.example.sostavslova.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HintRulesTest {
    private val source = "электростанция"
    private val words = listOf("кот", "крот", "торт", "рост", "рот", "ток", "нота", "елка", "колея", "станция")
    private val index = WordIndex(words)
    private val content = mapOf("станция" to HintWordData(
        category = HintCategory("transport", "Транспорт"),
        definition = "Место остановки поездов для посадки пассажиров",
        familiar = true,
    ))

    private fun start(vararg tasks: CampaignTask, stage: Int = 1, answers: List<String> = words) =
        CurrentCampaignLevel(CampaignLevel(1, source, stage, 1, tasks.toList()),
            GameState.start(source, WordIndex(answers)))

    private fun next(current: CurrentCampaignLevel, data: Map<String, HintWordData> = content) =
        HintRules.next(current, data, countMinLength = 4, random = Random(17))

    private fun accept(current: CurrentCampaignLevel, word: String) =
        TaskRules.submit(current, word, 4).evaluation.state

    @Test fun definitionAttributionSurvivesLadderAndIsNeverAttachedToOtherSteps() {
        val result = next(start(CampaignTask.T4("riddle", listOf("станция"))),
            content.mapValues { (_, data) -> data.copy(definitionWiktionary = true) })
        val ladder = result.state.hints.ladder
        assertTrue(ladder.steps.single { it.stage == HintStage.DEFINITION }.wiktionary)
        assertTrue(ladder.steps.filter { it.stage != HintStage.DEFINITION }.none { it.wiktionary })
        assertEquals(0, HintRules.nextCost(result.state))
        val paid = start(CampaignTask.T3("long", 7, "станция"))
        assertEquals(1, HintRules.nextCost(paid))
        assertEquals(1, HintRules.nextCost(paid.copy(hints = HintState(paidUnits = 5))))
    }

    @Test fun allStagesHaveSpecifiedBudgetAndChargeEveryOrdinaryStep() {
        for (stage in 1..6) {
            var current = start(CampaignTask.T3("long", 7, "станция"), stage = stage)
            val initial = listOf(5, 5, 4, 4, 3, 3)[stage - 1]
            assertEquals(initial, HintRules.budget(current).remaining)
            repeat(initial) { count ->
                val result = next(current)
                assertEquals(HintStatus.SHOWN, result.status)
                current = result.state
                assertEquals(count + 1, current.hints.shownSteps)
                assertEquals(count + 1, current.hints.paidUnits)
            }
            val exhausted = next(current)
            assertEquals(HintStatus.BUDGET_EXHAUSTED, exhausted.status)
            assertNull(exhausted.step)
            assertEquals(current, exhausted.state)
            assertEquals(exhausted, next(exhausted.state))
        }
    }

    @Test fun t4FreeStepsWorkAtZeroBudgetOnEveryStage() {
        for (stage in 1..6) {
            var current = start(CampaignTask.T4("riddle", listOf("станция")), stage = stage)
            val spent = HintRules.budget(current).remaining
            current = current.copy(hints = HintState(shownSteps = spent, paidUnits = spent))
            for (expected in listOf(HintStage.THEME, HintStage.DEFINITION, HintStage.LENGTH)) {
                val result = next(current)
                assertEquals(expected, assertNotNull(result.step).stage)
                assertEquals(HintStatus.SHOWN, result.status)
                current = result.state
                assertEquals(spent, current.hints.paidUnits)
                assertEquals(0, HintRules.budget(current).remaining)
            }
            assertEquals(spent + 3, current.hints.shownSteps)
            assertEquals(HintStatus.BUDGET_EXHAUSTED, next(current).status)
        }
    }

    @Test fun ladderRevealsOnePositionAtATimeUpToHalfAndAnswerDoesNotCountAsFound() {
        var current = start(CampaignTask.T4("riddle", listOf("станция")))
        val stages = mutableListOf<HintStage>()
        var positions = emptySet<Int>()
        repeat(7) {
            val result = next(current)
            val step = assertNotNull(result.step)
            stages += step.stage
            if (step.stage == HintStage.FIRST_LETTER || step.stage == HintStage.PATTERN) {
                assertEquals(1, (step.revealedPositions - positions).size)
                assertTrue(step.revealedPositions.containsAll(positions))
                assertTrue(step.revealedPositions.size <= "станция".length / 2)
                positions = step.revealedPositions
                assertFalse(step.text == "станция")
            }
            current = result.state
        }
        assertEquals(listOf(HintStage.THEME, HintStage.DEFINITION, HintStage.LENGTH,
            HintStage.FIRST_LETTER, HintStage.PATTERN, HintStage.PATTERN, HintStage.ANSWER), stages)
        assertEquals("станция", HintRules.currentStep(current)?.text)
        assertEquals(7, current.hints.shownSteps)
        assertEquals(4, current.hints.paidUnits)
        assertTrue(current.game.found.isEmpty())
        assertTrue(current.completedTaskIds.isEmpty())
        val complete = next(current)
        assertEquals(HintStatus.LADDER_COMPLETE, complete.status)
        assertEquals(current, complete.state)
        assertEquals(complete, next(complete.state))
    }

    @Test fun missingDatabaseAndThreeLetterWordSkipUnavailableStepsAndPattern() {
        var current = start(CampaignTask.T4("riddle", listOf("кот")), answers = listOf("кот"))
        for (stage in listOf(HintStage.LENGTH, HintStage.FIRST_LETTER, HintStage.ANSWER)) {
            val result = next(current, emptyMap())
            assertEquals(stage, result.step?.stage)
            current = result.state
        }
        assertEquals(3, current.hints.shownSteps)
        assertEquals(2, current.hints.paidUnits)
        current = accept(current, "кот")
        val noWords = next(current)
        assertEquals(HintStatus.NO_TARGET, noWords.status)
        assertNull(noWords.state.hints.ladder.target)
        assertEquals(2, noWords.state.hints.paidUnits)
        assertEquals(noWords, next(noWords.state))
    }

    @Test fun categoryCannotDiscloseAnswerOrRelatedWordsAndRepeatedTextCostsNothing() {
        for (category in listOf("Станция", "СТА́НЦИЯ", "Станционный транспорт", "<b>Транспорт</b>")) {
            val data = mapOf("станция" to HintWordData(category = HintCategory("transport", category)))
            val result = next(start(CampaignTask.T4("riddle", listOf("станция"))), data)
            assertEquals(HintStage.LENGTH, result.step?.stage, category)
            assertEquals(1, result.state.hints.shownSteps)
            assertEquals(0, result.state.hints.paidUnits)
        }
        val text = "Место остановки поездов для посадки пассажиров"
        val data = mapOf("станция" to HintWordData(category = HintCategory("transport", text), definition = text))
        val first = next(start(CampaignTask.T4("riddle", listOf("станция"))), data)
        val second = next(first.state, data)
        assertEquals(HintStage.LENGTH, second.step?.stage)
        assertEquals(2, second.state.hints.shownSteps)
        assertEquals(0, second.state.hints.paidUnits)
    }

    @Test fun definitionsArePreparedBeforeDisplay() {
        val result = next(start(CampaignTask.T4("riddle", listOf("станция"))),
            mapOf("станция" to HintWordData(definition = "Станция — место остановки поездов для посадки пассажиров")))
        assertEquals(HintStage.DEFINITION, result.step?.stage)
        val text = assertNotNull(result.step?.text)
        assertFalse("станция" in text.lowercase())
        assertTrue("***" in text)
    }

    @Test fun knownInitialEndingAndThemeAreNotSoldAgain() {
        var initial = start(CampaignTask.T2("initial", 'к', 2), answers = listOf("крот"))
        initial = next(initial, emptyMap()).state
        assertEquals(HintStage.PATTERN, next(initial, emptyMap()).step?.stage)
        assertEquals(1, initial.hints.paidUnits)
        var ending = start(CampaignTask.T5("ending", "от", 4), answers = listOf("крот"))
        ending = next(ending, emptyMap()).state
        ending = next(ending, emptyMap()).state
        assertEquals(HintStage.ANSWER, next(ending, emptyMap()).step?.stage)
        assertEquals(setOf(0, 2, 3), ending.hints.ladder.revealedPositions)
        val theme = next(start(CampaignTask.T6("theme", "transport", 1, listOf("станция"))))
        assertEquals(HintStage.DEFINITION, theme.step?.stage)
    }

    @Test fun fixedTargetSurvivesOtherAnswersAndCompletionOfItsTask() {
        var current = next(start(CampaignTask.T3("long", 4, "крот")), emptyMap()).state
        current = accept(current, "торт")
        assertTrue("long" in current.completedTaskIds)
        current = next(current, emptyMap()).state
        assertEquals("крот", current.hints.ladder.target?.word)
        val shown = current.hints.shownSteps
        current = accept(current, "крот")
        current = next(current, emptyMap()).state
        assertFalse(current.hints.ladder.target?.word in listOf("крот", "торт"))
        assertEquals(shown + 1, current.hints.shownSteps)
    }

    @Test fun riddlePrecedesEarlierTasksAndSwitchesOnlyAfterGuessing() {
        var current = next(start(CampaignTask.T1("count", 2),
            CampaignTask.T4("riddle", listOf("кот", "крот"))), emptyMap()).state
        assertEquals(TaskType.T4, current.hints.ladder.target?.taskType)
        val first = assertNotNull(current.hints.ladder.target).word
        current = accept(current, first)
        current = next(current, emptyMap()).state
        assertEquals(setOf("кот", "крот") - first, setOf(current.hints.ladder.target?.word))
        assertEquals(0, current.hints.paidUnits)
    }

    @Test fun firstEligibleUnfinishedTaskAndAllTaskFiltersAreUsed() {
        val tasks = listOf(
            CampaignTask.T1("count", 5) to setOf("крот", "торт", "рост", "нота", "елка", "колея", "станция"),
            CampaignTask.T2("initial", 'к', 2) to setOf("крот", "колея"),
            CampaignTask.T3("long", 4, "торт") to setOf("торт"),
            CampaignTask.T5("ending", "от", 4) to setOf("крот"),
            CampaignTask.T6("theme", "animals", 2, listOf("кот", "крот")) to setOf("кот", "крот"),
            CampaignTask.T7("letter", 'л', 2) to setOf("елка", "колея"),
        )
        for ((task, expected) in tasks) {
            val result = next(start(CampaignTask.T2("impossible", 'ы', 1), task,
                CampaignTask.T1("later", 9)), emptyMap())
            val target = assertNotNull(result.state.hints.ladder.target)
            assertEquals(task.id, target.taskId)
            assertTrue(target.word in expected, "$task: ${target.word}")
            if (task is CampaignTask.T7) assertEquals('л', target.letter)
            val completed = start(task, CampaignTask.T1("later", 9)).copy(completedTaskIds = setOf(task.id))
            assertEquals("later", next(completed, emptyMap()).state.hints.ladder.target?.taskId)
        }
        val short = start(CampaignTask.T1("count", 1), answers = listOf("кот"))
        val target = assertNotNull(HintRules.next(short, countMinLength = 3).state.hints.ladder.target)
        assertEquals("count", target.taskId)
    }

    @Test fun fallbackUsesBuildableUnfoundWordsAndPrefersFamiliarUnlabelledCandidates() {
        val data = mapOf("кот" to HintWordData(familiar = true, undesirableLabels = setOf("obsolete")),
            "крот" to HintWordData(familiar = true))
        val current = start(answers = listOf("кот", "крот", "торт", "молоко", source))
        val result = next(current, data)
        val target = assertNotNull(result.state.hints.ladder.target)
        assertEquals("крот", target.word)
        assertNull(target.taskId)
        val guessed = accept(current, "крот")
        assertEquals("торт", next(guessed, data).state.hints.ladder.target?.word)
        assertEquals(HintStatus.NO_TARGET, next(start(answers = listOf("молоко", source))).status)
    }

    @Test fun forbiddenTargetsAreExcludedEvenWhenAssignedButRemainValidInput() {
        for (word in listOf("прелюбодейка", "брудершафт", "праймериз")) {
            val sourceWord = word + "а"
            val current = CurrentCampaignLevel(CampaignLevel(1, sourceWord, 1, 1,
                listOf(CampaignTask.T4("riddle", listOf(word)))), GameState.start(sourceWord, WordIndex(listOf(word))))
            assertEquals(HintStatus.NO_TARGET, next(current).status)
            assertEquals(AnswerResult.CORRECT, current.game.submit(word).result)
        }
    }

    @Test fun fourDistinctNormalizedAnswersReplenishOnlyOnceAndIncludeThreeLetterWords() {
        var current = next(start(CampaignTask.T3("long", 7, "станция"), stage = 6)).state
        current = next(current).state
        current = next(current).state
        assertEquals(HintStatus.BUDGET_EXHAUSTED, next(current).status)
        for (word in listOf("кот", "рот", "ток")) current = accept(current, word)
        assertEquals(0, HintRules.budget(current).remaining)
        current = accept(current, "ёлка")
        assertEquals(1, HintRules.budget(current).earned)
        assertEquals(1, HintRules.budget(current).remaining)
        val repeated = current.copy(game = current.game.copy(found = current.game.found + listOf(" ЕЛКА ", "ёлка", "кот")))
        assertEquals(HintRules.budget(current), HintRules.budget(repeated))
        current = next(current).state
        assertEquals(4, current.hints.paidUnits)
        assertEquals(HintStatus.BUDGET_EXHAUSTED, next(current).status)
        for (word in listOf("крот", "торт", "рост", "нота")) current = accept(current, word)
        assertEquals(2, HintRules.budget(current).earned)
        assertEquals(1, HintRules.budget(current).remaining)
    }

    @Test fun seedReproducesTargetAndPatternIndependentOfInputOrder() {
        val current = start(answers = listOf("станция", "колея"))
        val first = HintRules.next(current, random = Random(42))
        val second = HintRules.next(current.copy(game = current.game.copy(allWords = current.game.allWords.reversed())), random = Random(42))
        assertEquals(first.state.hints, second.state.hints)
        assertEquals(first.state.hints, HintRules.next(current, random = Random(42)).state.hints)
    }

    @Test fun restartAndRepeatedReadsPreserveLadderContentPositionsAndExpensesWithoutDatabase() {
        var current = start(CampaignTask.T4("riddle", listOf("станция")))
        repeat(5) { current = next(current).state }
        val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(current.level))
        val saved = CampaignRules.recordLevel(campaign, CampaignProgress(), current)
        val reopened = assertNotNull(CampaignRules.openLevel(campaign, saved, 1, index))
        assertEquals(current.hints, reopened.hints)
        repeat(3) {
            assertEquals(current.hints.ladder.shown.last(), HintRules.currentStep(reopened))
            assertEquals(HintRules.budget(current), HintRules.budget(reopened))
            assertEquals(saved, CampaignRules.recordLevel(campaign, saved, reopened))
        }
        assertEquals(next(current), next(reopened, emptyMap()))
        val stale = CampaignRules.recordLevel(campaign, saved, start(*current.level.tasks.toTypedArray()))
        assertEquals(current.hints, stale.levels[source]?.hints)
        assertEquals(HintState(), assertNotNull(CampaignRules.openLevel(campaign, CampaignProgress(), 1, index)).hints)
        assertEquals(HintState(), assertNotNull(CampaignRules.openLevel(campaign, CampaignRules.reset(), 1, index)).hints)
    }

    @Test fun paidT4StepsExhaustBudgetOnEveryStageWithoutRepeatingFreeSteps() {
        for (stage in 1..6) {
            var current = start(CampaignTask.T4("riddle", listOf("станция")), stage = stage)
            repeat(3) { current = next(current).state }
            val initial = HintRules.budget(current).initial
            repeat(minOf(initial, 4)) { count ->
                val result = next(current)
                assertEquals(HintStatus.SHOWN, result.status)
                current = result.state
                assertEquals(count + 1, current.hints.paidUnits)
                assertEquals(count + 4, current.hints.shownSteps)
            }
            assertEquals(if (initial < 4) HintStatus.BUDGET_EXHAUSTED else HintStatus.LADDER_COMPLETE,
                next(current).status)
        }
    }

    @Test fun repeatedLettersNeverRevealMultiplePositionsAndShortWordsHaveNoFullPattern() {
        for (word in listOf("ток", "торт", "каска", "кассета", "тетерев")) {
            val sourceWord = word + "а"
            var current = CurrentCampaignLevel(CampaignLevel(1, sourceWord, 1, 1,
                listOf(CampaignTask.T4("riddle", listOf(word)))), GameState.start(sourceWord, WordIndex(listOf(word))))
            var positions = emptySet<Int>()
            do {
                val result = next(current, emptyMap())
                val step = assertNotNull(result.step)
                if (step.stage == HintStage.FIRST_LETTER || step.stage == HintStage.PATTERN) {
                    assertEquals(1, (step.revealedPositions - positions).size)
                    assertTrue(step.revealedPositions.size <= word.length / 2)
                    positions = step.revealedPositions
                    if (step.stage == HintStage.PATTERN) {
                        val pattern = assertNotNull(step.text)
                        assertEquals(word.length, pattern.length)
                        assertEquals(positions.size, pattern.count { it != '_' })
                        assertTrue('_' in pattern)
                    }
                }
                current = result.state
            } while (step.stage != HintStage.ANSWER)
            assertEquals(word, HintRules.currentStep(current)?.text)
        }
    }

    @Test fun t7RevealsItsLetterAtOnePositionAndDoesNotConfuseOccurrenceWithInitial() {
        var current = start(CampaignTask.T7("letter", 'т', 1), answers = listOf("станция"))
        current = next(current, emptyMap()).state
        val first = next(current, emptyMap())
        assertEquals(HintStage.FIRST_LETTER, first.step?.stage)
        assertEquals("с", first.step?.text)
        val pattern = next(first.state, emptyMap())
        assertEquals(HintStage.PATTERN, pattern.step?.stage)
        assertEquals("ст_____", pattern.step?.text)
    }

    @Test fun relatedCategoryAndUnusableDefinitionAreSkippedWithoutCost() {
        val data = mapOf("кот" to HintWordData(category = HintCategory("animals", "Кошачьи"),
            definition = "То же, что кот", related = setOf("кошка")))
        val result = next(start(CampaignTask.T4("riddle", listOf("кот"))), data)
        assertEquals(HintStage.LENGTH, result.step?.stage)
        assertEquals(1, result.state.hints.shownSteps)
        assertEquals(0, result.state.hints.paidUnits)
    }

    @Test fun blockedSelectionStaysFixedAndEmptyLadderPersistsAfterLastAnswer() {
        var current = start(answers = listOf("кот", "крот"), stage = 6)
            .copy(hints = HintState(shownSteps = 3, paidUnits = 3))
        val blocked = next(current, emptyMap())
        assertEquals(HintStatus.BUDGET_EXHAUSTED, blocked.status)
        val campaign = Campaign(listOf(Stage(6, 1, 1)), listOf(current.level))
        var saved = CampaignRules.recordLevel(campaign, CampaignProgress(), blocked.state)
        current = assertNotNull(CampaignRules.openLevel(campaign, saved, 1, index))
        assertEquals(blocked.state.hints, current.hints)
        val retried = HintRules.next(current, random = Random(989))
        assertEquals(current, retried.state)

        for (word in current.game.allWords) current = accept(current, word)
        current = next(current).state
        assertNull(current.hints.ladder.target)
        saved = CampaignRules.recordLevel(campaign, saved, current)
        val reopened = assertNotNull(CampaignRules.openLevel(campaign, saved, 1, index))
        assertEquals(current.hints, reopened.hints)
        assertNull(HintRules.currentStep(reopened))
        assertEquals(HintStatus.NO_TARGET, next(reopened).status)
    }
}
