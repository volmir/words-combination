package com.example.sostavslova.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HintUniquenessTest {
    private val index = WordIndex(listOf("станция", "колея"))
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), listOf(
        CampaignLevel(1, "электростанция", 1, 1, listOf(
            CampaignTask.T4("riddle", listOf("станция")), CampaignTask.T1("count", 9))),
        CampaignLevel(2, "электростанцияя", 1, 1, listOf(CampaignTask.T1("count2", 9))),
    ))

    private fun open(saved: CampaignProgress, number: Int) =
        assertNotNull(CampaignRules.openLevel(campaign, saved, number, index))

    @Test fun shownFreeRiddleIsAvoidedAfterTaskSwitchAndOnAnotherLevel() {
        val initial = CampaignProgress(highestUnlockedLevel = 2)
        val hint = HintRules.next(open(initial, 1), random = Random(1))
        assertEquals(0, hint.state.hints.paidUnits)
        val saved = CampaignRules.recordLevel(campaign, initial, hint.state)
        assertEquals(setOf("станция"), saved.hintedWords)
        val selected = HintRules.selectTask(open(saved, 1), "count")
        val sameLevel = HintRules.next(selected, hintedWords = saved.hintedWords)
        assertEquals("колея", sameLevel.state.hints.ladder.target?.word)
        val secondLevel = HintRules.next(open(saved, 2), hintedWords = saved.hintedWords)
        assertEquals("колея", secondLevel.state.hints.ladder.target?.word)
        assertEquals(saved, CampaignRules.recordLevel(campaign, saved, hint.state))
        val continued = HintRules.next(hint.state, hintedWords = saved.hintedWords)
        assertEquals("станция", continued.state.hints.ladder.target?.word)
    }

    @Test fun selectionAndBlockedPurchaseDoNotRecordWordButShownStepDoes() {
        val initial = CampaignProgress()
        val selected = HintRules.selectTask(open(initial, 1), "count")
        assertTrue(CampaignRules.recordLevel(campaign, initial, selected).hintedWords.isEmpty())
        val blocked = HintRules.next(selected.copy(hints = selected.hints.copy(paidUnits = 100)))
        assertEquals(HintStatus.BUDGET_EXHAUSTED, blocked.status)
        assertTrue(CampaignRules.recordLevel(campaign, initial, blocked.state).hintedWords.isEmpty())
        val shown = HintRules.next(selected)
        val saved = CampaignRules.recordLevel(campaign, initial, shown.state)
        assertEquals(setOf(shown.state.hints.ladder.target?.word), saved.hintedWords)
    }

    @Test fun unseenWordOutranksFamiliarityAndLengthButExhaustedPoolStillWorks() {
        val current = open(CampaignProgress(highestUnlockedLevel = 2), 2)
        val data = mapOf("станция" to HintWordData(familiar = true), "колея" to HintWordData(familiar = false))
        val unseen = HintRules.next(current, data, hintedWords = setOf("станция"))
        assertEquals("колея", unseen.state.hints.ladder.target?.word)
        val exhausted = HintRules.next(current, data, hintedWords = index.words.toSet())
        assertEquals(HintStatus.SHOWN, exhausted.status)
        assertEquals("станция", exhausted.state.hints.ladder.target?.word)
        val riddle = HintRules.next(open(CampaignProgress(), 1), hintedWords = index.words.toSet())
        assertEquals("станция", riddle.state.hints.ladder.target?.word)
    }

    @Test fun riddlePoolIgnoresHistoryEvenWhenAnUnseenAlternativeExists() {
        val initial = open(CampaignProgress(), 1)
        val current = initial.copy(level = initial.level.copy(tasks = listOf(
            CampaignTask.T4("riddle", index.words))))
        val chosen = (0..50).map { seed ->
            HintRules.next(current, random = Random(seed), hintedWords = setOf("станция"))
                .state.hints.ladder.target?.word
        }.toSet()
        assertEquals(index.words.toSet(), chosen)
    }

    @Test fun equalQualityUsesInjectedRandomAcrossDifferentLengthsAndT3PreferredTarget() {
        val answers = listOf("аааааа", "ааааааа", "аааааааа")
        val source = answers.joinToString("")
        val current = CurrentCampaignLevel(CampaignLevel(1, source, 1, 1,
            listOf(CampaignTask.T3("long", HintEconomy.TARGET_MIN_LENGTH, answers.first()))),
            GameState.start(source, WordIndex(answers)))
        val targets = (0..50).map {
            HintRules.next(current, random = Random(it)).state.hints.ladder.target?.word
        }.toSet()
        assertEquals(answers.toSet(), targets)
        assertNotEquals(1, targets.size)
    }
}
