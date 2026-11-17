package com.example.sostavslova.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HintRulesTest {
    @Test fun upgradeDropsImportedStepsWithoutChangingBudgetTargetOrLetterProgress() {
        val state = HintState(HintLadder(HintTarget("станция"), listOf(
            HintStep(HintStage.THEME, "Транспорт"),
            HintStep(HintStage.DEFINITION, "Синтетический прежний текст", wiktionary = true),
            HintStep(HintStage.LENGTH, length = 7),
            HintStep(HintStage.FIRST_LETTER, "с", revealedPositions = setOf(0)),
            HintStep(HintStage.ANSWER, "станция"),
        ), nextStepIndex = 4, revealedPositions = setOf(0)), shownSteps = 6, paidUnits = 3)
        val migrated = state.withoutLegacyDefinitions()
        assertEquals(state.ladder.target, migrated.ladder.target)
        assertEquals(3, migrated.ladder.nextStepIndex)
        assertEquals(setOf(0), migrated.ladder.revealedPositions)
        assertEquals(6, migrated.shownSteps)
        assertEquals(3, migrated.paidUnits)
        assertTrue(migrated.ladder.steps.none { it.stage == HintStage.DEFINITION })
        assertEquals(migrated, migrated.withoutLegacyDefinitions())
        assertEquals(listOf(HintStage.THEME, HintStage.LENGTH, HintStage.FIRST_LETTER), migrated.ladder.shown.map { it.stage })
        assertEquals(1, state.copy(ladder = state.ladder.copy(nextStepIndex = 1)).withoutLegacyDefinitions().ladder.nextStepIndex)
    }
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

    @Test fun metadataNeverCreatesStepsAndOnlyFirstT4LetterIsFree() {
        val first = next(start(CampaignTask.T4("riddle", listOf("станция"))))
        assertEquals("с______", first.step?.text)
        assertEquals(HintStage.FIRST_LETTER, first.step?.stage)
        assertEquals(0, first.state.hints.paidUnits)
        assertEquals(1, HintRules.nextCost(first.state))
        assertTrue(first.state.hints.ladder.steps.all { it.stage in setOf(
            HintStage.FIRST_LETTER, HintStage.PATTERN, HintStage.ANSWER) })
        assertTrue(first.state.hints.ladder.steps.none { it.wiktionary || it.length != null })
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

    @Test fun t4FirstLetterWorksAtZeroBudgetOnEveryStage() {
        for (stage in 1..6) {
            val initial = start(CampaignTask.T4("riddle", listOf("станция")), stage = stage)
            val spent = HintRules.budget(initial).remaining
            val current = initial.copy(hints = HintState(shownSteps = spent, paidUnits = spent))
            assertEquals(0, HintRules.nextCost(current))
            val result = next(current)
            assertEquals("с______", result.step?.text)
            assertEquals(spent, result.state.hints.paidUnits)
            assertEquals(spent + 1, result.state.hints.shownSteps)
            assertEquals(1, HintRules.nextCost(result.state))
            val blocked = next(result.state)
            assertEquals(HintStatus.BUDGET_EXHAUSTED, blocked.status)
            assertEquals(result.state, blocked.state)
        }
    }

    @Test fun ladderRevealsEveryPositionThenPaidAnswerWithoutCountingItAsFound() {
        var current = start(CampaignTask.T4("riddle", listOf("станция")))
        // Six paid steps require one earned unit as well as the initial five.
        for (word in listOf("кот", "рот", "ток", "торт")) current = accept(current, word)
        var positions = emptySet<Int>()
        repeat(7) { count ->
            val result = next(current)
            val step = assertNotNull(result.step)
            assertEquals(1, (step.revealedPositions - positions).size)
            assertTrue(step.revealedPositions.containsAll(positions))
            positions = step.revealedPositions
            assertEquals(if (count == 6) HintStage.ANSWER else if (count == 0)
                HintStage.FIRST_LETTER else HintStage.PATTERN, step.stage)
            assertEquals(7, step.text?.length)
            assertEquals(count + 1, step.text?.count { it != '_' })
            current = result.state
        }
        assertEquals("станция", HintRules.currentStep(current)?.text)
        assertEquals(7, current.hints.shownSteps)
        assertEquals(6, current.hints.paidUnits)
        assertFalse("станция" in current.game.found)
        assertTrue(current.completedTaskIds.isEmpty())
        assertEquals(HintStatus.LADDER_COMPLETE, next(current).status)
        assertEquals(current, next(current).state)
    }

    @Test fun missingDatabaseAndThreeLetterWordSkipUnavailableStepsAndPattern() {
        var current = start(CampaignTask.T4("riddle", listOf("кот")), answers = listOf("кот"))
        for (stage in listOf(HintStage.FIRST_LETTER, HintStage.PATTERN, HintStage.ANSWER)) {
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

    @Test fun metadataCannotAffectLetterLadder() {
        val current = start(CampaignTask.T4("riddle", listOf("станция")))
        val empty = next(current, emptyMap())
        for (data in listOf(content, mapOf("станция" to HintWordData(
            category = HintCategory("transport", "Станция"), definition = "станция")))) {
            assertEquals(empty.state.hints, next(current, data).state.hints)
        }
    }

    @Test fun legacyMigrationPreservesOnlyAcquiredLettersAndNeverChargesOnRead() {
        val initial = start(CampaignTask.T4("riddle", listOf("станция")))
        val steps = listOf(HintStep(HintStage.THEME, "Транспорт"),
            HintStep(HintStage.DEFINITION, "Синтетическое описание"),
            HintStep(HintStage.LENGTH, length = 7),
            HintStep(HintStage.FIRST_LETTER, "с", revealedPositions = setOf(0)),
            HintStep(HintStage.PATTERN, "ст_____", revealedPositions = setOf(0, 1)),
            HintStep(HintStage.ANSWER, "станция", revealedPositions = (0..6).toSet()))
        for (open in 0..steps.size) {
            val acquired = when { open == 6 -> (0..6).toSet(); open == 5 -> setOf(0, 1); open == 4 -> setOf(0); else -> emptySet() }
            val old = initial.copy(hints = HintState(HintLadder(HintTarget("станция", "riddle", TaskType.T4),
                steps, open, acquired), shownSteps = 12, paidUnits = 4))
            val restored = HintRules.restore(old)
            assertEquals(old.game, restored.game)
            assertEquals(12, restored.hints.shownSteps)
            assertEquals(4, restored.hints.paidUnits)
            assertEquals(acquired, restored.hints.ladder.revealedPositions)
            assertEquals(acquired.size, restored.hints.ladder.shown.lastOrNull()?.revealedPositions?.size ?: 0)
            assertTrue(restored.hints.ladder.shown.all { it.text?.length == 7 })
            assertTrue(restored.hints.ladder.steps.none { it.stage in setOf(HintStage.THEME, HintStage.DEFINITION, HintStage.LENGTH) })
            assertEquals(restored, HintRules.restore(restored))
            assertEquals(restored, HintRules.restore(old))
            if (open < 6) {
                assertEquals(if (open < 4) 0 else 1, HintRules.nextCost(restored))
                val next = next(restored)
                assertEquals(if (open < 4) 4 else 5, next.state.hints.paidUnits)
                assertEquals(1, (assertNotNull(next.step).revealedPositions - acquired).size)
            } else assertEquals(HintStatus.LADDER_COMPLETE, next(restored).status)
        }
    }

    @Test fun knownInitialAndEndingAreNotSoldAgainEvenForShortWords() {
        val cases = listOf(
            Triple(CampaignTask.T2("initial", 'к', 2), "крот", setOf(0)),
            Triple(CampaignTask.T5("ending", "от", 4), "крот", setOf(2, 3)),
            Triple(CampaignTask.T5("ending", "рот", 4), "крот", setOf(1, 2, 3)))
        for ((task, word, known) in cases) {
            var current = start(task, answers = listOf(word))
            var positions = known
            repeat(word.length - known.size) {
                val result = next(current, emptyMap())
                val step = assertNotNull(result.step)
                assertEquals(1, (step.revealedPositions - positions).size)
                assertTrue(step.revealedPositions.containsAll(positions))
                assertEquals(1, result.state.hints.paidUnits - current.hints.paidUnits)
                positions = step.revealedPositions
                current = result.state
            }
            assertEquals(HintStage.ANSWER, HintRules.currentStep(current)?.stage)
            assertEquals(word, HintRules.currentStep(current)?.text)
            assertEquals(HintStatus.LADDER_COMPLETE, next(current).status)
        }
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
        repeat(3) { current = next(current).state }
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
            var current = next(start(CampaignTask.T4("riddle", listOf("станция")), stage = stage)).state
            val initial = HintRules.budget(current).initial
            repeat(initial) { count ->
                val result = next(current)
                assertEquals(HintStatus.SHOWN, result.status)
                current = result.state
                assertEquals(count + 1, current.hints.paidUnits)
                assertEquals(count + 2, current.hints.shownSteps)
            }
            assertEquals(HintStatus.BUDGET_EXHAUSTED, next(current).status)
        }
    }

    @Test fun repeatedLettersNeverRevealMultiplePositionsAndShortWordsHaveNoFullPattern() {
        for (word in listOf("ток", "торт", "каска", "кассета", "тетерев")) {
            val sourceWord = word + "домдыммохдно"
            var current = CurrentCampaignLevel(CampaignLevel(1, sourceWord, 1, 1,
                listOf(CampaignTask.T4("riddle", listOf(word)))), GameState.start(sourceWord, WordIndex(listOf(word, "дом", "дым", "мох", "дно"))))
            for (answer in listOf("дом", "дым", "мох", "дно")) current = accept(current, answer)
            var positions = emptySet<Int>()
            do {
                val result = next(current, emptyMap())
                val step = assertNotNull(result.step)
                if (step.stage == HintStage.FIRST_LETTER || step.stage == HintStage.PATTERN) {
                    assertEquals(1, (step.revealedPositions - positions).size)
                    assertTrue(step.revealedPositions.size < word.length)
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
        val first = next(current, emptyMap())
        assertEquals(HintStage.FIRST_LETTER, first.step?.stage)
        assertEquals("с______", first.step?.text)
        val pattern = next(first.state, emptyMap())
        assertEquals(HintStage.PATTERN, pattern.step?.stage)
        assertEquals("ст_____", pattern.step?.text)
    }

    @Test fun relatedCategoryAndUnusableDefinitionAreSkippedWithoutCost() {
        val data = mapOf("кот" to HintWordData(category = HintCategory("animals", "Кошачьи"),
            definition = "То же, что кот", related = setOf("кошка")))
        val result = next(start(CampaignTask.T4("riddle", listOf("кот"))), data)
        assertEquals(HintStage.FIRST_LETTER, result.step?.stage)
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
