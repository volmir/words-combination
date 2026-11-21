package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверка именно установленной short15-кампании, а не синтетического 30/100 стенда. */
class Short15ProductionCampaignTest {
    private val campaign = requireNotNull(TestData.campaign) { "В data/manifest.json нет campaign_source" }

    private fun earn(progress: CampaignProgress, level: CampaignLevel, tasks: Int): CampaignProgress {
        val current = CurrentCampaignLevel(
            level = level,
            game = GameState(level.word, emptyList()),
            completedTaskIds = level.tasks.take(tasks).map { it.id }.toSet(),
        )
        return CampaignRules.recordLevel(campaign, progress, current)
    }

    @Test fun installedCampaignHasFifteenLevelsSixStagesAndEveryTaskType() {
        assertEquals(15, campaign.levels.size)
        assertEquals(49, campaign.maxStars)
        assertEquals(listOf(3, 2, 3, 2, 3, 2), campaign.stages.map { it.lastLevel - it.firstLevel + 1 })
        assertEquals(TaskType.entries.toSet(), campaign.levels.flatMap { it.tasks }.map { it.type }.toSet())
        assertEquals(4, campaign.levels.last().requiredStars)
        assertEquals("short15-release-v1", TestData.campaignProfile)
        assertTrue(ProductionCampaignValidator.validate(campaign, "short15-release-v1").isEmpty())
        assertEquals(listOf("чемпионство", "вегетарианец", "аккумулятор"), campaign.levels.take(3).map { it.word })
    }

    @Test fun installedShort1505BalanceAndDictionaryMatchApprovedConditions() {
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24),
            campaign.levels.flatMap { it.tasks.filterIsInstance<CampaignTask.T1>() }.map { it.n })
        assertEquals(listOf(3, 3, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7),
            campaign.levels.flatMap { it.tasks.filterIsInstance<CampaignTask.T2>() }.map { it.n })
        val level = campaign.levels.last()
        val answers = TestData.index.findBuildable(level.word)
        val game = GameState(level.word, answers)
        assertFalse(TestData.index.contains("менталист"))
        assertEquals(AnswerResult.NOT_FOUND, game.submit("менталист", TestData.index).result)
        val t3 = level.tasks.filterIsInstance<CampaignTask.T3>().single()
        assertEquals(8, t3.minLength)
        assertEquals("антитело", t3.hintTarget)
        assertEquals(24, answers.count { it.length >= 8 })
        assertEquals(AnswerResult.CORRECT, game.submit("антитело", TestData.index).result)
        val t5 = level.tasks.filterIsInstance<CampaignTask.T5>().single()
        assertEquals(7, t5.minLength)
        assertEquals("ист", t5.ending)
        val expected = "акмеист аметист атомист дантист дуалист каноист окулист танкист медалист меланист мелодист методист моделист уклонист дуатлонист колумнист талмудист анекдотист".split(" ").toSet()
        assertEquals(expected, answers.filter { it.length >= t5.minLength && it.endsWith(t5.ending) }.toSet())
        val t7 = level.tasks.filterIsInstance<CampaignTask.T7>().single()
        assertEquals('у', t7.letter)
        assertEquals(8, t7.n)
        val short = answers.first { it.length == 3 && 'у' in it }
        val progress = TaskRules.evaluate(CurrentCampaignLevel(level, game.submit(short).state), campaign.countMinLength)
        assertEquals(1, progress.progress.single { it.id == t7.id }.current)
        assertEquals(0, progress.progress.single { it.id == level.tasks.filterIsInstance<CampaignTask.T1>().single().id }.current)
        val t6 = campaign.levels[13].tasks.filterIsInstance<CampaignTask.T6>().single()
        assertEquals(4, t6.n)
        assertEquals("professions", t6.category)
        assertEquals("артист артистка банкир бариста батрак радист акробат актриса ботаник дантист диктор санитар танкист каратист бортник канонир таксатор диакон бонна кантор".split(" ").toSet(), t6.words.toSet())
        assertEquals(20, t6.words.size)
        val professionAnswers = TestData.index.findBuildable(campaign.levels[13].word).toSet()
        assertTrue(t6.words.all { it in professionAnswers })
    }

    @Test fun fourOfFiveReachesFinaleButMasterNeedsAllFortyNine() {
        var progress = CampaignProgress()
        for (level in campaign.levels.dropLast(1)) {
            progress = earn(progress, level, level.tasks.size)
        }
        assertEquals(15, progress.highestUnlockedLevel)
        assertFalse(progress.finaleReached)

        val finalLevel = campaign.levels.last()
        val four = earn(progress, finalLevel, 4)
        assertEquals(4, CampaignRules.stars(finalLevel, four))
        assertTrue(four.finaleReached)
        assertFalse(CampaignRules.summary(campaign, four).isMaster)
        assertEquals(48, CampaignRules.summary(campaign, four).stars)
        assertEquals(finalLevel, CampaignRules.continueLevel(campaign, four))

        // Повторный заход и добор не отнимают финал и не открывают чужие уровни.
        val revisited = earn(four, campaign.levels.first(), 0)
        assertEquals(48, CampaignRules.summary(campaign, revisited).stars)
        assertTrue(revisited.finaleReached)

        val five = earn(revisited, finalLevel, 5)
        val summary = CampaignRules.summary(campaign, five)
        assertEquals(49, summary.stars)
        assertEquals(49, summary.maxStars)
        assertTrue(summary.isMaster)
        assertEquals(15, summary.passedLevels)
        assertTrue(five.finaleReached)
        assertEquals(15, five.highestUnlockedLevel)
        assertEquals(finalLevel, CampaignRules.continueLevel(campaign, five))
    }
}
