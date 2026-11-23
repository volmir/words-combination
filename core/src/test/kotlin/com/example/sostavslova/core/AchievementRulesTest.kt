package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Правила выдачи восьми достижений за уровень; все пороги берутся из AchievementRules. */
class AchievementRulesTest {
    private fun level(requiredStars: Int = 1, taskCount: Int = 2) = CampaignLevel(
        number = 1,
        word = "образец",
        stage = 1,
        requiredStars = requiredStars,
        tasks = (1..taskCount).map { CampaignTask.T1("t$it", 1) },
    )

    private fun game(words: List<String>, found: List<String> = emptyList()) =
        GameState("образец", words, found)

    private fun words(count: Int): List<String> = (0 until count).map {
        "ба" + Alphabet.LETTERS[it / Alphabet.SIZE] + Alphabet.LETTERS[it % Alphabet.SIZE]
    }

    private fun evaluate(
        found: List<String>,
        total: Int,
        requiredStars: Int = 1,
        taskCount: Int = 2,
        completedTaskIds: Set<String> = emptySet(),
        hintsUsed: Boolean = true,
        alreadyEarned: Set<LevelAchievement> = emptySet(),
    ): Set<LevelAchievement> {
        val all = (found.distinct() + words(total)).distinct()
        return AchievementRules.evaluate(
            level = level(requiredStars, taskCount),
            game = game(all, found),
            completedTaskIds = completedTaskIds,
            hints = HintState(),
            alreadyEarned = alreadyEarned,
            hintsUsed = hintsUsed,
        )
    }

    @Test fun fractionalThresholdsAreCeilAndNeverFireOnEmptyDictionary() {
        assertFalse(AchievementRules.meetsShare(33, 100, 1, 3))
        assertTrue(AchievementRules.meetsShare(34, 100, 1, 3))
        assertFalse(AchievementRules.meetsShare(49, 100, 1, 2))
        assertTrue(AchievementRules.meetsShare(50, 100, 1, 2))
        assertFalse(AchievementRules.meetsShare(66, 100, 2, 3))
        assertTrue(AchievementRules.meetsShare(67, 100, 2, 3))
        assertTrue(AchievementRules.meetsShare(1, 3, 1, 3))
        assertFalse(AchievementRules.meetsShare(0, 3, 1, 3))
        assertFalse(AchievementRules.meetsShare(0, 0, 1, 3))
        assertFalse(AchievementRules.meetsShare(1, 0, 1, 3))

        // total == 0 проверен отдельно: он не даёт долевых достижений ни при каком found.
        for (total in 1..120) {
            for ((numerator, denominator) in listOf(1 to 3, 1 to 2, 2 to 3, 3 to 3)) {
                val ceil = (total * numerator + denominator - 1) / denominator
                for (found in 0..total) {
                    assertEquals(found >= ceil, AchievementRules.meetsShare(found, total, numerator, denominator),
                        "found=$found total=$total share=$numerator/$denominator")
                }
            }
        }

        assertEquals(emptySet(), evaluate(found = emptyList(), total = 0))
    }

    @Test fun expertCollectorAndExplorerUseExactFractionBoundaries() {
        val all = words(100)
        assertFalse(LevelAchievement.EXPERT in evaluate(all.take(33), total = 100))
        assertTrue(LevelAchievement.EXPERT in evaluate(all.take(34), total = 100))
        assertFalse(LevelAchievement.COLLECTOR in evaluate(all.take(49), total = 100))
        assertTrue(LevelAchievement.COLLECTOR in evaluate(all.take(50), total = 100))
        assertFalse(LevelAchievement.EXPLORER in evaluate(all.take(66), total = 100))
        assertTrue(LevelAchievement.EXPLORER in evaluate(all.take(67), total = 100))
    }

    @Test fun sharesAndAvailabilityUseUniqueNormalizedWordsOfAtLeastThreeLetters() {
        val state = game(listOf("ЁЖ", "еж", "кот", "КОТ", "рот", "торт"), listOf("КОТ", "кот", "еж"))
        val earned = AchievementRules.evaluate(level(), state, emptySet(), HintState(), emptySet())
        assertEquals(setOf(LevelAchievement.EXPERT), earned)
        assertEquals(emptySet(), AchievementRules.available(game(listOf("я", "еж")), 1, 1))
        assertEquals(emptySet(), AchievementRules.evaluate(
            level(), game(listOf("я", "еж"), listOf("я", "еж")), emptySet(), HintState(), emptySet()))
    }

    @Test fun lengthAchievementsUseSixSevenEightAndNineLetters() {
        val six = "абвгде"
        val seven = "абвгдеж"
        val sevenMore = listOf("бвгдежз", "вгдежзи")
        val eight = "абвгдежз"
        val nine = "абвгдежзи"

        assertFalse(LevelAchievement.LONG_WORD in evaluate(listOf(six), total = 1))
        assertTrue(LevelAchievement.LONG_WORD in evaluate(listOf(seven), total = 1))

        val twoLong = listOf(seven, sevenMore[0])
        assertTrue(LevelAchievement.LONG_WORD in evaluate(twoLong, total = 3))
        assertFalse(LevelAchievement.LONG_WORD_MASTER in evaluate(twoLong, total = 3))
        assertTrue(LevelAchievement.LONG_WORD_MASTER in evaluate(twoLong + sevenMore[1], total = 4))

        assertFalse(LevelAchievement.GIANT in evaluate(listOf(eight), total = 1))
        assertTrue(LevelAchievement.GIANT in evaluate(listOf(nine), total = 1))
    }

    @Test fun straightARequiresOptionalTasksAndEveryStar() {
        val allTasks = setOf("t1", "t2")
        assertFalse(LevelAchievement.STRAIGHT_A in evaluate(
            emptyList(), total = 1, requiredStars = 2, taskCount = 2, completedTaskIds = allTasks))
        assertFalse(LevelAchievement.STRAIGHT_A in evaluate(
            emptyList(), total = 1, requiredStars = 1, taskCount = 2, completedTaskIds = setOf("t1")))
        assertTrue(LevelAchievement.STRAIGHT_A in evaluate(
            emptyList(), total = 1, requiredStars = 1, taskCount = 2, completedTaskIds = allTasks))
    }

    @Test fun noHintsRequiresPassedThresholdAndNoShownStep() {
        val already = setOf("t1")
        assertFalse(LevelAchievement.NO_HINTS in evaluate(emptyList(), total = 1,
            completedTaskIds = emptySet(), hintsUsed = false))
        assertTrue(LevelAchievement.NO_HINTS in evaluate(emptyList(), total = 1,
            completedTaskIds = already, hintsUsed = false))
        assertFalse(LevelAchievement.NO_HINTS in evaluate(emptyList(), total = 1,
            completedTaskIds = already, hintsUsed = true))
        // Признак устойчив: старый снимок без поля всё равно трактуется как «подсказка была».
        assertEquals(emptySet(), AchievementRules.evaluate(
            level(), game(words(1)), already, HintState(shownSteps = 0, paidUnits = 2), emptySet()))
    }

    @Test fun freeFirstT4StepDropsNoHintsAndMarksUsage() {
        val target = "торт"
        val current = CurrentCampaignLevel(
            level = CampaignLevel(1, "трактор", 1, 1, listOf(CampaignTask.T4("riddle", listOf(target)))),
            game = GameState("трактор", listOf(target)),
        )
        val result = HintRules.next(current, mapOf(target to HintWordData()))
        assertEquals(HintStatus.SHOWN, result.status)
        assertEquals(0, result.state.hints.paidUnits)
        assertTrue(result.state.hints.shownSteps > 0)
        assertTrue(result.state.hints.ladder.shown.first().stage != HintStage.ANSWER)
        val solved = TaskRules.submit(result.state, target, countMinLength = 3).evaluation.state
        assertEquals(setOf("riddle"), solved.completedTaskIds)
        val earned = AchievementRules.evaluate(current.level, solved.game,
            solved.completedTaskIds, solved.hints, emptySet(),
            hintsUsed = result.state.hints.shownSteps > 0 || result.state.hints.paidUnits > 0)
        assertFalse(LevelAchievement.NO_HINTS in earned)
    }

    @Test fun earnedAchievementsSurviveHintDictionaryAndThresholdChanges() {
        val earned = AchievementRules.evaluate(level(requiredStars = 1), game(words(10), words(10)),
            setOf("t1", "t2"), HintState(), emptySet(), hintsUsed = false)
        assertTrue(earned.containsAll(listOf(
            LevelAchievement.NO_HINTS, LevelAchievement.EXPERT, LevelAchievement.COLLECTOR,
            LevelAchievement.EXPLORER, LevelAchievement.STRAIGHT_A)))

        val after = AchievementRules.evaluate(level(requiredStars = 2), game(emptyList()),
            emptySet(), HintState(shownSteps = 7, paidUnits = 3), earned, hintsUsed = true)
        assertEquals(earned, after)
        assertEquals(emptyList(), (after - earned).toList())
    }

    @Test fun repeatedEvaluationNeverGrantsTheSameAchievementTwice() {
        val first = evaluate(words(10), total = 10, completedTaskIds = setOf("t1", "t2"), hintsUsed = false)
        val second = evaluate(words(10), total = 10, completedTaskIds = setOf("t1", "t2"),
            hintsUsed = false, alreadyEarned = first)
        assertEquals(first, second)
    }

    @Test fun severalAchievementsAreOrderedAndRepeatIndependentlyOnOtherLevels() {
        val first = evaluate(words(10) + listOf("абвгдежзи"), total = 10,
            completedTaskIds = setOf("t1", "t2"), hintsUsed = false)
        assertEquals(
            listOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT, LevelAchievement.COLLECTOR,
                LevelAchievement.EXPLORER, LevelAchievement.LONG_WORD, LevelAchievement.GIANT,
                LevelAchievement.STRAIGHT_A),
            first.toList(),
        )
        val other = evaluate(words(10) + listOf("абвгдежзи"), total = 10,
            completedTaskIds = setOf("t1", "t2"), hintsUsed = false)
        assertEquals(first, other)
        assertEquals(emptySet(), other - first)
    }

    @Test fun availabilityFollowsCurrentDictionaryAndOptionalTasks() {
        fun available(words: List<String>, requiredStars: Int = 1, taskCount: Int = 2) =
            AchievementRules.available(game(words), requiredStars, taskCount)

        // «Отличник» зависит от R < M, даже если текущий словарь опустел.
        assertEquals(setOf(LevelAchievement.STRAIGHT_A), available(emptyList()))
        assertEquals(emptySet(), available(emptyList(), requiredStars = 2, taskCount = 2))
        assertEquals(
            setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT, LevelAchievement.COLLECTOR,
                LevelAchievement.EXPLORER, LevelAchievement.STRAIGHT_A),
            available(words(3)),
        )
        assertEquals(setOf(), available(words(3), requiredStars = 2, taskCount = 2)
            .intersect(setOf(LevelAchievement.STRAIGHT_A)))
        assertTrue(LevelAchievement.LONG_WORD in available(words(3) + listOf("абвгдеж")))
        assertFalse(LevelAchievement.LONG_WORD_MASTER in available(words(3) + listOf("абвгдеж", "бвгдежз")))
        assertTrue(LevelAchievement.LONG_WORD_MASTER in available(words(3) +
            listOf("абвгдеж", "бвгдежз", "вгдежзи")))
        assertFalse(LevelAchievement.GIANT in available(words(3) + listOf("абвгдежз")))
        assertTrue(LevelAchievement.GIANT in available(words(3) + listOf("абвгдежзи")))
    }

    @Test fun productionLevelsWithoutLongWordsHaveNoGiantAvailable() {
        val campaign = assertNotNull(TestData.campaign)
        val index = TestData.index
        for (number in listOf(1, 2, 3, 5, 6)) {
            val level = campaign.levels[number - 1]
            val game = GameState.start(level.word, index)
            assertFalse(LevelAchievement.GIANT in AchievementRules.available(game, level.requiredStars, level.maxStars),
                "level $number has a nine-letter word")
        }
        for (number in 9..15) {
            val level = campaign.levels[number - 1]
            assertTrue(level.requiredStars < level.maxStars)
            val game = GameState.start(level.word, index)
            assertTrue(LevelAchievement.STRAIGHT_A in AchievementRules.available(game, level.requiredStars, level.maxStars))
        }
    }

    @Test fun evaluationIsPureAndDoesNotTouchStarsFrontierOrHintBudget() {
        val campaign = Campaign(listOf(Stage(1, 1, 2)), listOf(
            CampaignLevel(1, "первый", 1, 1, listOf(CampaignTask.T1("a", 1))),
            CampaignLevel(2, "второй", 1, 2, listOf(CampaignTask.T1("b", 1), CampaignTask.T1("c", 1))),
        ))
        val progress = CampaignProgress(
            levels = mapOf("первый" to CampaignLevelProgress(
                found = listOf("пот"), completedTaskIds = setOf("a"),
                hints = HintState(shownSteps = 2, paidUnits = 1))),
            highestUnlockedLevel = 2,
        )
        val current = CurrentCampaignLevel(campaign.levels[0], game(listOf("пот"), listOf("пот")),
            setOf("a"), HintState(shownSteps = 2, paidUnits = 1))
        val beforeProgress = progress
        val beforeBudget = HintRules.budget(current)
        val beforeStars = CampaignRules.stars(campaign.levels[0], progress)

        val earned = AchievementRules.evaluate(campaign.levels[0], current.game,
            current.completedTaskIds, current.hints, emptySet(), hintsUsed = true)
        val again = AchievementRules.evaluate(campaign.levels[0], current.game,
            current.completedTaskIds, current.hints, earned, hintsUsed = true)

        assertEquals(beforeProgress, progress)
        assertEquals(beforeBudget, HintRules.budget(current))
        assertEquals(beforeStars, CampaignRules.stars(campaign.levels[0], progress))
        assertTrue(CampaignRules.isUnlocked(campaign, progress, 2))
        assertEquals(earned, again)
        assertEquals(emptySet(), again - earned)
    }
}
