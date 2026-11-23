package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LEX11-01: адресок исключён, T3 уровня 11 нацелен на постель, T5 не изменён. */
class Lex1101CampaignTest {
    private val campaign = requireNotNull(TestData.campaign) { "В data/manifest.json нет campaign_source" }
    private val level = campaign.levels.single { it.number == 11 }
    private val answers = TestData.index.findBuildable(level.word)

    @Test
    fun adresokIsExcludedAndPostelIsTheT3Target() {
        assertEquals(20103, TestData.index.size)
        assertFalse(TestData.index.contains("адресок"))
        assertTrue(TestData.index.contains("постель"))
        assertEquals("подстрекатель", level.word)

        val t3 = level.tasks.filterIsInstance<CampaignTask.T3>().single()
        assertEquals("постель", t3.hintTarget)
        assertEquals(7, t3.minLength)

        val game = GameState(level.word, answers)
        assertEquals(AnswerResult.NOT_FOUND, game.submit("адресок", TestData.index).result)
        assertEquals(AnswerResult.CORRECT, game.submit("постель", TestData.index).result)
    }

    @Test
    fun t3StillAcceptsOtherLongAnswers() {
        val t3 = level.tasks.filterIsInstance<CampaignTask.T3>().single()
        val other = answers.first { it.length >= t3.minLength && it != t3.hintTarget }
        val submission = TaskRules.submit(CurrentCampaignLevel(level, GameState(level.word, answers)),
            other, campaign.countMinLength, TestData.index)
        assertEquals(AnswerResult.CORRECT, submission.result)
        assertTrue(t3.id in submission.evaluation.newlyCompletedTaskIds)
    }

    @Test
    fun t5ConfigurationIsUnchangedAndRemainsReachable() {
        val t5 = level.tasks.filterIsInstance<CampaignTask.T5>().single()
        assertEquals("ок", t5.ending)
        assertEquals(5, t5.minLength)
        assertEquals(1, level.tasks.count { it.id == "l11-t5" })

        val accepted = answers.filter { it.length >= t5.minLength && it.endsWith(t5.ending) }.toSet()
        assertTrue(accepted.isNotEmpty())
        assertFalse("адресок" in accepted)
        assertTrue("песок" in accepted)

        val submission = TaskRules.submit(CurrentCampaignLevel(level, GameState(level.word, answers)),
            "песок", campaign.countMinLength, TestData.index)
        assertEquals(AnswerResult.CORRECT, submission.result)
        assertTrue(t5.id in submission.evaluation.newlyCompletedTaskIds)
    }
}
