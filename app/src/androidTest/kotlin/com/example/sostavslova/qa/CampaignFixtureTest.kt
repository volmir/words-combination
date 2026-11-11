package com.example.sostavslova.qa

import com.example.sostavslova.core.*
import com.example.sostavslova.data.CampaignJson
import org.junit.Assert.*
import org.junit.Test

/** Чистая проверка тестового стенда; может исполняться JUnit на JVM без устройства. */
class CampaignFixtureTest {
    private val campaign = CampaignJson.decode(QaCampaign.json)
    private val index = WordIndex(QaCampaign.words)

    private fun answer(saved: CampaignProgress, number: Int, word: String): CampaignProgress {
        val current = requireNotNull(CampaignRules.openLevel(campaign, saved, number, index))
        val result = TaskRules.submit(current, word, campaign.countMinLength, index)
        assertEquals(word, AnswerResult.CORRECT, result.result)
        return CampaignRules.recordLevel(campaign, saved, result.evaluation.state)
    }

    @Test fun fixtureHasAllTypesDistinctFinalBoundaryAndEveryTaskIsReachable() {
        assertTrue(CampaignValidator.validate(campaign).isEmpty())
        assertEquals(3, campaign.levels.size)
        assertEquals(8, campaign.maxStars)
        assertEquals(TaskType.entries.toSet(), campaign.levels.flatMap { it.tasks }.map { it.type }.toSet())
        assertTrue(campaign.levels.all { it.requiredStars < it.maxStars })
        assertEquals(2, campaign.levels.last().requiredStars)
        for (level in campaign.levels) {
            val game = GameState.start(level.word, index)
            val evaluation = TaskRules.evaluate(CurrentCampaignLevel(level, game.copy(found = game.allWords)), 4)
            assertEquals(level.word, level.maxStars, evaluation.stars)
        }
    }

    @Test fun exactUiRouteReachesFinaleAtSevenAndMasterAtEightWithoutGlobalAnswerExclusion() {
        var saved = CampaignProgress()
        assertNull(CampaignRules.openLevel(campaign, saved, 3, index))
        saved = answer(saved, 1, "кот")
        assertEquals(setOf("riddle1"), saved.levels.getValue("картотека").completedTaskIds)
        assertEquals(2, saved.highestUnlockedLevel)
        saved = answer(saved, 2, "кот")
        assertEquals(3, saved.highestUnlockedLevel)
        saved = answer(saved, 1, "крот")
        saved = answer(saved, 1, "торт")
        saved = answer(saved, 2, "трек")
        saved = answer(saved, 3, "кот")
        assertFalse(saved.finaleReached)
        saved = answer(saved, 3, "крот")
        assertTrue(saved.finaleReached)
        assertEquals(7, CampaignRules.summary(campaign, saved).stars)
        assertFalse(CampaignRules.summary(campaign, saved).isMaster)
        saved = saved.copy(finaleShown = true)
        saved = answer(saved, 3, "карта")
        saved = answer(saved, 3, "река")
        val summary = CampaignRules.summary(campaign, saved)
        assertEquals(9, summary.foundWords)
        assertTrue(summary.isMaster)
        val duplicate = TaskRules.submit(requireNotNull(CampaignRules.openLevel(campaign, saved, 3, index)),
            "крот", 4, index)
        assertEquals(AnswerResult.ALREADY_FOUND, duplicate.result)
        assertEquals(saved, CampaignRules.recordLevel(campaign, saved, duplicate.evaluation.state))
        assertTrue(saved.finaleShown)
    }

    @Test fun alternateThresholdsAndAllThematicPairsRemainReachable() {
        var saved = answer(CampaignProgress(), 1, "крот")
        assertEquals(setOf("initial1"), saved.levels.getValue("картотека").completedTaskIds)
        saved = answer(saved, 2, "трек")
        assertEquals(setOf("long2"), saved.levels.getValue("электротрактор").completedTaskIds)
        for (pair in listOf(listOf("кот", "крот"), listOf("кот", "рак"), listOf("крот", "рак"))) {
            var route = saved
            for (word in pair) route = answer(route, 3, word)
            assertEquals(pair.toString(), setOf("theme3", "contains3"),
                route.levels.getValue("электрокарта").completedTaskIds)
            assertTrue(route.finaleReached)
        }
    }
}
