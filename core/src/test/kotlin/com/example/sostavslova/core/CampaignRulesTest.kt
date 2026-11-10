package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignRulesTest {
    private val index = WordIndex(listOf("кот", "крот", "торт", "елка"))

    private fun level(number: Int, m: Int = 5, r: Int = 3): CampaignLevel = CampaignLevel(
        number, "электростанция" + Alphabet.LETTERS[number - 1], (number - 1) / 5 + 1, r,
        listOf(
            CampaignTask.T1("$number-count", 10),
            CampaignTask.T2("$number-initial", 'к', 10),
            CampaignTask.T3("$number-length", 7, "станция"),
            CampaignTask.T4("$number-riddle", listOf("торт")),
            CampaignTask.T5("$number-ending", "ка", 4),
        ).take(m),
    )

    private fun mini(m: Int = 5, r: Int = 3) = Campaign(
        listOf(Stage(1, 1, 3)), (1..3).map { level(it, m, r) },
    )

    private fun current(level: CampaignLevel, ids: Set<String>, found: List<String> = emptyList()) =
        CurrentCampaignLevel(level, GameState.start(level.word, index, found), ids)

    private fun earn(campaign: Campaign, progress: CampaignProgress, number: Int, stars: Int): CampaignProgress {
        val level = campaign.levels[number - 1]
        return CampaignRules.recordLevel(campaign, progress,
            current(level, level.tasks.take(stars).map { it.id }.toSet()))
    }

    private fun reachFinale(campaign: Campaign): CampaignProgress = campaign.levels.fold(CampaignProgress()) { state, level ->
        earn(campaign, state, level.number, level.requiredStars)
    }

    private fun production(): Campaign {
        val stages = (1..6).map { Stage(it, (it - 1) * 5 + 1, it * 5) }
        return Campaign(stages, (1..30).map { number ->
            val stage = (number - 1) / 5 + 1
            level(number, minOf(stage, 5), if (number == 30) 4 else listOf(1, 1, 2, 2, 3, 3)[stage - 1])
        })
    }

    @Test fun newCampaignOpensOnlyFirstAndRejectsClosedOrInvalidRoutes() {
        val campaign = mini()
        val progress = CampaignProgress()
        assertEquals(1, progress.highestUnlockedLevel)
        assertFalse(progress.finaleReached)
        assertFalse(progress.finaleShown)
        assertEquals(campaign.levels.first(), CampaignRules.continueLevel(campaign, progress))
        assertNotNull(CampaignRules.openLevel(campaign, progress, 1, index))
        for (number in listOf(-1, 0, 2, 3, 4, Int.MAX_VALUE)) {
            assertFalse(CampaignRules.isUnlocked(campaign, progress, number))
            assertNull(CampaignRules.openLevel(campaign, progress, number, index))
        }
        assertFailsWith<IllegalArgumentException> {
            earn(campaign, progress, 2, 5)
        }
        assertFailsWith<IllegalArgumentException> {
            earn(campaign, progress, 3, 5)
        }
        assertEquals(progress, CampaignRules.consumeFinaleDialog(progress).state)
        assertFalse(CampaignRules.consumeFinaleDialog(progress).showDialog)
    }

    @Test fun everySubsetAtEveryThresholdUnlocksExactlyWhenRDistinctTasksAreDone() {
        for (m in 1..5) for (r in 1..m) {
            val campaign = mini(m, r)
            val first = campaign.levels.first()
            for (mask in 0 until (1 shl m)) {
                val ids = first.tasks.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.map { it.id }.toSet()
                val progress = CampaignRules.recordLevel(campaign, CampaignProgress(), current(first, ids))
                val passed = ids.size >= r
                val context = "M=$m R=$r mask=$mask"
                assertEquals(ids.size, CampaignRules.stars(first, progress), context)
                assertEquals(passed, CampaignRules.isPassed(first, progress), context)
                assertEquals(if (passed) 2 else 1, progress.highestUnlockedLevel, context)
                assertEquals(passed, CampaignRules.isUnlocked(campaign, progress, 2), context)
                assertFalse(CampaignRules.isUnlocked(campaign, progress, 3), context)
                assertFalse(progress.finaleReached, context)
                assertEquals(if (passed) 2 else 1, CampaignRules.continueLevel(campaign, progress).number, context)
                assertEquals(progress, CampaignRules.recordLevel(campaign, progress, current(first, ids)), context)
            }
        }
    }

    @Test fun revisitingAndToppingUpOldLevelsNeverMovesFrontierOrContinueBackwards() {
        val campaign = mini()
        val passedFirst = earn(campaign, CampaignProgress(), 1, 3)
        assertEquals(2, CampaignRules.continueLevel(campaign, passedFirst).number)
        val passedSecond = earn(campaign, passedFirst, 2, 3)
        assertEquals(3, passedSecond.highestUnlockedLevel)
        for (number in 1..3) assertNotNull(CampaignRules.openLevel(campaign, passedSecond, number, index))
        val toppedUp = earn(campaign, passedSecond, 1, 5)
        assertEquals(3, toppedUp.highestUnlockedLevel)
        assertEquals(3, CampaignRules.continueLevel(campaign, toppedUp).number)
        val repeated = earn(campaign, toppedUp, 1, 0)
        assertEquals(toppedUp, repeated)
        assertNotNull(CampaignRules.openLevel(campaign, repeated, 1, index))
        assertFalse(repeated.finaleReached)
    }

    @Test fun storedFrontierIsNotRecomputedFromIncompleteLevelSaves() {
        val campaign = mini()
        val restored = CampaignProgress(highestUnlockedLevel = 3)
        assertFalse(CampaignRules.isPassed(campaign.levels.first(), restored))
        assertNotNull(CampaignRules.openLevel(campaign, restored, 3, index))
        assertEquals(1, CampaignRules.continueLevel(campaign, restored).number)
        val updated = earn(campaign, restored, 1, 0)
        assertEquals(3, updated.highestUnlockedLevel)
        val final = updated.copy(finaleReached = true, finaleShown = true)
        val revisit = earn(campaign, final, 1, 0)
        assertEquals(final, revisit)
        assertFalse(CampaignRules.consumeFinaleDialog(revisit).showDialog)
    }

    @Test fun oneAnswerAwardsTwoStarsAndUnlocksImmediatelyWithoutDoubleAward() {
        val first = level(1, 2, 2).copy(tasks = listOf(
            CampaignTask.T1("count", 1), CampaignTask.T2("initial", 'к', 1),
        ))
        val campaign = mini().copy(levels = listOf(first) + mini().levels.drop(1))
        val opened = assertNotNull(CampaignRules.openLevel(campaign, CampaignProgress(), 1, index))
        val answer = TaskRules.submit(opened, "крот", campaign.countMinLength)
        assertEquals(AnswerResult.CORRECT, answer.result)
        assertEquals(2, answer.evaluation.newlyCompletedTaskIds.size)
        val progress = CampaignRules.recordLevel(campaign, CampaignProgress(), answer.evaluation.state)
        assertEquals(2, progress.highestUnlockedLevel)
        assertEquals(2, CampaignRules.stars(first, progress))
        val reopened = assertNotNull(CampaignRules.openLevel(campaign, progress, 1, index))
        val duplicate = TaskRules.submit(reopened, "КРОТ", campaign.countMinLength)
        assertEquals(AnswerResult.ALREADY_FOUND, duplicate.result)
        assertTrue(duplicate.evaluation.newlyCompletedTaskIds.isEmpty())
        assertEquals(progress, CampaignRules.recordLevel(campaign, progress, duplicate.evaluation.state))
    }

    @Test fun finalRequiresFourOfFiveAndDialogIsConsumedOnlyOnce() {
        val campaign = production()
        val before = campaign.levels.dropLast(1).fold(CampaignProgress()) { state, level ->
            earn(campaign, state, level.number, level.requiredStars)
        }
        val three = earn(campaign, before, 30, 3)
        assertFalse(three.finaleReached)
        assertFalse(CampaignRules.consumeFinaleDialog(three).showDialog)
        assertEquals(30, CampaignRules.continueLevel(campaign, three).number)
        val four = earn(campaign, three, 30, 4)
        assertTrue(four.finaleReached)
        assertFalse(four.finaleShown)
        assertEquals(30, four.highestUnlockedLevel)
        assertFalse(CampaignRules.summary(campaign, four).isMaster)
        assertEquals(6, CampaignRules.continueLevel(campaign, four).number)
        val dialog = CampaignRules.consumeFinaleDialog(four)
        assertTrue(dialog.showDialog)
        assertTrue(dialog.state.finaleReached)
        assertTrue(dialog.state.finaleShown)
        assertFalse(CampaignRules.consumeFinaleDialog(dialog.state).showDialog)
        val toppedUpFinal = earn(campaign, dialog.state, 30, 5)
        assertEquals(30, toppedUpFinal.highestUnlockedLevel)
        assertTrue(toppedUpFinal.finaleReached)
        assertFalse(CampaignRules.summary(campaign, toppedUpFinal).isMaster)
        assertEquals(6, CampaignRules.continueLevel(campaign, toppedUpFinal).number)
        assertFalse(CampaignRules.consumeFinaleDialog(toppedUpFinal).showDialog)
        val oldLevel = earn(campaign, toppedUpFinal, 6, 2)
        assertEquals(30, oldLevel.highestUnlockedLevel)
        assertEquals(7, CampaignRules.continueLevel(campaign, oldLevel).number)
        assertFalse(CampaignRules.consumeFinaleDialog(oldLevel).showDialog)
        for (number in 1..30) assertNotNull(CampaignRules.openLevel(campaign, oldLevel, number, index))
    }

    @Test fun everyFinalSubsetUsesRWithoutRequiringASpecificTask() {
        for (m in 1..5) for (r in 1..m) {
            val last = level(1, m, r)
            val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(last))
            for (mask in 0 until (1 shl m)) {
                val ids = last.tasks.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.map { it.id }.toSet()
                val state = CampaignRules.recordLevel(campaign, CampaignProgress(), current(last, ids))
                assertEquals(ids.size >= r, state.finaleReached, "M=$m R=$r mask=$mask")
                assertEquals(1, state.highestUnlockedLevel)
                assertEquals(last, CampaignRules.continueLevel(campaign, state))
            }
        }
    }

    @Test fun hundredStarsMeansMasterAndContinueSelectsLastWhileAnyCardStillWorks() {
        val campaign = production()
        val final = reachFinale(campaign)
        val almostComplete = campaign.levels.dropLast(1).fold(final) { state, level ->
            earn(campaign, state, level.number, level.maxStars)
        }
        assertEquals(99, CampaignRules.summary(campaign, almostComplete).stars)
        assertFalse(CampaignRules.summary(campaign, almostComplete).isMaster)
        assertEquals(30, CampaignRules.continueLevel(campaign, almostComplete).number)
        val complete = earn(campaign, almostComplete, 30, 5)
        val summary = CampaignRules.summary(campaign, complete)
        assertEquals(100, summary.stars)
        assertEquals(100, summary.maxStars)
        assertEquals(30, summary.passedLevels)
        assertEquals(30, summary.totalLevels)
        assertTrue(summary.isMaster)
        assertTrue(complete.finaleReached)
        assertEquals(30, complete.highestUnlockedLevel)
        assertEquals(30, CampaignRules.continueLevel(campaign, complete).number)
        for (number in 1..30) assertNotNull(CampaignRules.openLevel(campaign, complete, number, index))
    }

    @Test fun summaryCountsUniqueNormalizedAnswersPerLevelAndOnlyCurrentCampaignKeysAndTasks() {
        val campaign = mini()
        val first = campaign.levels[0]
        val second = campaign.levels[1]
        val progress = CampaignProgress(
            levels = mapOf(
                first.word to CampaignLevelProgress(listOf("ёлка", "елка", " ЁЛКА ", "кот"),
                    first.tasks.take(3).map { it.id }.toSet() + "obsolete"),
                second.word to CampaignLevelProgress(listOf("кот", "КОТ"), setOf(second.tasks.first().id)),
                "удаленный" to CampaignLevelProgress(listOf("торт"), setOf("old")),
            ),
            highestUnlockedLevel = 2,
        )
        val summary = CampaignRules.summary(campaign, progress)
        assertEquals(3, summary.foundWords)
        assertEquals(4, summary.stars)
        assertEquals(15, summary.maxStars)
        assertEquals(1, summary.passedLevels)
        assertEquals(3, summary.totalLevels)
        assertFalse(summary.isMaster)
        val replaced = campaign.copy(levels = listOf(first.copy(word = "картотека")) + campaign.levels.drop(1))
        assertEquals(0, CampaignRules.stars(replaced.levels.first(), progress))
        assertEquals(1, CampaignRules.summary(replaced, progress).foundWords)
        assertEquals(2, progress.highestUnlockedLevel)
    }

    @Test fun recordingMergesSavedAnswersAndStarsAndRecoversTasksFromFound() {
        val campaign = mini()
        val first = campaign.levels.first()
        val initial = CampaignRules.recordLevel(campaign, CampaignProgress(), current(first, setOf(first.tasks[0].id), listOf("кот")))
        val recovered = CampaignRules.recordLevel(campaign, initial, current(first, emptySet(), listOf("торт")))
        assertEquals(setOf(first.tasks[0].id, first.tasks[3].id), recovered.levels.getValue(first.word).completedTaskIds)
        assertEquals(listOf("кот", "торт"), recovered.levels.getValue(first.word).found)
        val reopened = assertNotNull(CampaignRules.openLevel(campaign, recovered, 1, index))
        assertEquals(recovered.levels.getValue(first.word).found, reopened.game.found)
        assertEquals(recovered.levels.getValue(first.word).completedTaskIds, reopened.completedTaskIds)
        assertFailsWith<IllegalArgumentException> {
            CampaignRules.recordLevel(campaign, recovered, reopened.copy(game = GameState("картотека", emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            CampaignRules.recordLevel(campaign, recovered, reopened.copy(level = first.copy(word = "картотека")))
        }
    }

    @Test fun explicitResetClearsEveryLevelFrontierAndFinaleFlagsAndAllowsANewFinale() {
        val campaign = production()
        var complete = reachFinale(campaign)
        for (level in campaign.levels) complete = earn(campaign, complete, level.number, level.maxStars)
        complete = CampaignRules.recordLevel(campaign, complete, current(campaign.levels.first(), emptySet(), listOf("кот")))
        complete = CampaignRules.consumeFinaleDialog(complete).state
        assertTrue(complete.finaleShown)
        assertEquals(1, CampaignRules.summary(campaign, complete).foundWords)
        val reset = CampaignRules.reset()
        assertEquals(CampaignProgress(), reset)
        assertTrue(reset.levels.isEmpty())
        assertEquals(1, reset.highestUnlockedLevel)
        assertFalse(reset.finaleReached)
        assertFalse(reset.finaleShown)
        assertEquals(0, CampaignRules.summary(campaign, reset).stars)
        assertEquals(0, CampaignRules.summary(campaign, reset).foundWords)
        assertEquals(0, CampaignRules.summary(campaign, reset).passedLevels)
        assertFalse(CampaignRules.summary(campaign, reset).isMaster)
        assertEquals(1, CampaignRules.continueLevel(campaign, reset).number)
        assertNull(CampaignRules.openLevel(campaign, reset, 30, index))
        val reachedAgain = campaign.levels.fold(reset) { state, level -> earn(campaign, state, level.number, level.requiredStars) }
        assertTrue(CampaignRules.consumeFinaleDialog(reachedAgain).showDialog)
    }
}
