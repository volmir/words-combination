package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UXR-05: installed campaign, runtime submissions and every local R threshold. */
class CriticalUxCampaignAcceptanceTest {
    private val campaign = requireNotNull(TestData.campaign)

    @Test fun allFifteenLevelsAwardExactlyFortyNineStarsThroughRealSubmissions() {
        var progress = CampaignProgress()
        for (level in campaign.levels) {
            var current = requireNotNull(CampaignRules.openLevel(campaign, progress, level.number, TestData.index))
            for (answer in current.game.allWords) {
                val result = TaskRules.submit(current, answer, campaign.countMinLength, TestData.index)
                assertEquals(AnswerResult.CORRECT, result.result, "${level.number}: $answer")
                assertTrue(result.evaluation.state.completedTaskIds.containsAll(current.completedTaskIds))
                current = result.evaluation.state
                val countTask = level.tasks.filterIsInstance<CampaignTask.T1>().single()
                assertEquals(current.game.found.count { it.length >= campaign.countMinLength }.coerceAtMost(countTask.n),
                    TaskRules.evaluate(current, campaign.countMinLength).progress.single { it.id == countTask.id }.current)
            }
            assertEquals(level.maxStars, current.completedTaskIds.size, "level=${level.number}")
            progress = CampaignRules.recordLevel(campaign, progress, current)
        }
        val summary = CampaignRules.summary(campaign, progress)
        assertEquals(15, summary.passedLevels)
        assertEquals(49, summary.stars)
        assertTrue(summary.isMaster)
    }

    @Test fun everyTaskSubsetOfInstalledLevelsUnlocksOnlyAtItsRequiredThreshold() {
        var previous = CampaignProgress()
        for (level in campaign.levels) {
            for (mask in 0 until (1 shl level.tasks.size)) {
                val ids = level.tasks.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                    .map { it.id }.toSet()
                val current = CurrentCampaignLevel(level, GameState(level.word, emptyList()), ids)
                val saved = CampaignRules.recordLevel(campaign, previous, current)
                if (level.number < campaign.levels.size) {
                    assertEquals(if (ids.size >= level.requiredStars) level.number + 1 else level.number,
                        saved.highestUnlockedLevel, "level=${level.number}, tasks=$ids")
                } else {
                    assertEquals(ids.size >= level.requiredStars, saved.finaleReached, "final tasks=$ids")
                }
            }
            previous = CampaignRules.recordLevel(campaign, previous,
                CurrentCampaignLevel(level, GameState(level.word, emptyList()), level.tasks.map { it.id }.toSet()))
        }
    }
}
