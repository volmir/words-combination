package com.example.sostavslova.core

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject

/** Installed short15 + exactly the LCH-02 source used by the reproducible DB. */
class OwnedHintsTest {
    private val campaign = requireNotNull(TestData.campaign)
    private val authored = JSONObject(File("../docs/owned_hints.json").readText()).getJSONArray("entries")
    private val lexical = JSONObject(File("../tools/owned_hint_index.json").readText()).getJSONObject("word_metadata")
    private fun JSONObject.words(key: String): Set<String> = getJSONArray(key).let { array ->
        (0 until array.length()).map { array.getString(it) }.toSet()
    }
    private val data = (0 until authored.length()).associate { i ->
        val entry = authored.getJSONObject(i)
        val word = entry.getString("word")
        val metadata = lexical.getJSONObject(word)
        word to HintWordData(definition = entry.getString("hint"),
            forms = metadata.words("forms"), related = metadata.words("related"))
    }

    @Test fun everyMandatoryT4HasUsableOwnedRiddleAndCanBeSolved() {
        var count = 0
        for (level in campaign.levels) for (task in level.tasks.filterIsInstance<CampaignTask.T4>()) {
            var current = CurrentCampaignLevel(level.copy(tasks = listOf(task), requiredStars = 1),
                GameState.start(level.word, TestData.index))
            for (target in task.targets) {
                val info = assertNotNull(data[target])
                assertEquals(info.definition, DefinitionHint.prepare(target, assertNotNull(info.definition), info.forms, info.related))
                val hint = HintRules.next(current, data, random = Random(13))
                assertEquals(HintStage.FIRST_LETTER, hint.step?.stage)
                assertFalse(assertNotNull(hint.step).wiktionary)
                assertEquals(0, hint.state.hints.paidUnits)
                val selected = assertNotNull(hint.state.hints.ladder.target).word
                assertTrue(selected in task.targets && selected !in current.game.found)
                assertTrue(hint.state.game.found == current.game.found)
                current = TaskRules.submit(hint.state, selected, campaign.countMinLength).evaluation.state
                count++
            }
            assertTrue(task.id in current.completedTaskIds)
        }
        assertEquals(11, count)
        assertEquals(15, campaign.levels.size)
        assertEquals(49, campaign.maxStars)
        assertEquals(4, campaign.levels.last().requiredStars)
    }

    @Test fun t3AcceptsEveryValidLongAnswerIndependentlyOfSemanticCoverage() {
        var nonDesignated = 0
        var withoutDescription = 0
        for (level in campaign.levels) for (task in level.tasks.filterIsInstance<CampaignTask.T3>()) {
            val initial = CurrentCampaignLevel(level.copy(tasks = listOf(task), requiredStars = 1),
                GameState.start(level.word, TestData.index))
            for (answer in initial.game.allWords.filter { it.length >= task.minLength }) {
                val result = TaskRules.submit(initial, answer, campaign.countMinLength)
                assertEquals(AnswerResult.CORRECT, result.result)
                assertTrue(task.id in result.evaluation.state.completedTaskIds, answer)
                if (answer != task.hintTarget) nonDesignated++
                if (answer !in data) withoutDescription++
            }
        }
        assertTrue(nonDesignated > 0)
        assertTrue(withoutDescription > 0)
    }

    @Test fun t6AcceptsFourFromEntireApprovedPoolWithNoDescriptions() {
        val level = campaign.levels.single { it.tasks.any { task -> task is CampaignTask.T6 } }
        val task = level.tasks.filterIsInstance<CampaignTask.T6>().single()
        assertEquals(20, task.words.size)
        assertEquals(4, task.n)
        for (start in task.words.indices) {
            var current = CurrentCampaignLevel(level.copy(tasks = listOf(task), requiredStars = 1),
                GameState.start(level.word, TestData.index))
            repeat(4) { offset ->
                val word = task.words[(start + offset) % task.words.size]
                assertEquals("professions", lexical.getJSONObject(word).getString("category"))
                val hint = HintRules.next(current, emptyMap(), random = Random(offset))
                assertEquals(HintStage.FIRST_LETTER, hint.step?.stage)
                assertTrue(hint.state.hints.ladder.target?.word in task.words)
                current = TaskRules.submit(current, word, campaign.countMinLength).evaluation.state
                assertEquals(offset == 3, task.id in current.completedTaskIds)
            }
        }
    }

    @Test fun arbitraryTargetSkipsDefinitionAndChangesOnlyAfterItsAnswerIsEntered() {
        val level = campaign.levels.first()
        var current = CurrentCampaignLevel(level.copy(tasks = listOf(CampaignTask.T1("open", 100))),
            GameState.start(level.word, TestData.index))
        val first = HintRules.next(current, data, random = Random(2))
        current = first.state
        val answer = assertNotNull(current.hints.ladder.target).word
        assertEquals(HintStage.FIRST_LETTER, first.step?.stage)
        assertEquals(1, current.hints.shownSteps)
        assertEquals(1, current.hints.paidUnits)
        val remaining = HintRules.budget(current).remaining
        val again = HintRules.next(current, emptyMap(), random = Random(7))
        assertEquals(answer, again.state.hints.ladder.target?.word)
        assertEquals(remaining - 1, HintRules.budget(again.state).remaining)
        current = TaskRules.submit(again.state, answer, campaign.countMinLength).evaluation.state
        val next = HintRules.next(current, data, random = Random(2))
        assertFalse(next.state.hints.ladder.target?.word == answer)
        assertEquals(current.hints.shownSteps + 1, next.state.hints.shownSteps)
    }
}
