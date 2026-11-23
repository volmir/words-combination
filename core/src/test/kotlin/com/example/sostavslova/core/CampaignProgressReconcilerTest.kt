package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignProgressReconcilerTest {
    private val index = WordIndex(listOf(
        "река", "каре", "перец", "пир", "перо", "клоп", "пункт", "рекуперация", "опровержение", "хлопкопункт",
    ))

    private fun campaign(vararg words: String) = Campaign(
        listOf(Stage(1, 1, words.size)),
        words.mapIndexed { i, word ->
            CampaignLevel(i + 1, word, 1, 1, listOf(CampaignTask.T1("l${i + 1}-t1", 1)))
        },
    )

    private fun levelProgress(found: List<String>, tasks: Set<String>, hints: HintState = HintState()) =
        CampaignLevelProgress(found, tasks, hints)

    @Test fun sameWordsKeepFoundStarsFrontierAndFinale() {
        val campaign = campaign("рекуперация", "опровержение")
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре", "река"), setOf("l1-t1"))),
            highestUnlockedLevel = 2,
            finaleReached = true,
            finaleShown = true,
            campaignId = CampaignIdentity.LEGACY_SHORT15_SHA,
        )
        val result = CampaignProgressReconciler.reconcile(saved, campaign, index)
        assertTrue(result.isCompatible)
        assertEquals(CampaignIdentity.of(campaign), result.state.campaignId)
        assertEquals(listOf("каре", "река"), result.state.levels.getValue("рекуперация").found)
        assertTrue("l1-t1" in result.state.levels.getValue("рекуперация").completedTaskIds)
        assertEquals(2, result.state.highestUnlockedLevel)
        assertTrue(result.state.finaleReached)
        assertTrue(result.state.finaleShown)
        assertTrue(result.changes.any { it.kind == ReconciliationChangeKind.CAMPAIGN_ID_UPDATED })
    }

    @Test fun changedThresholdKeepsAlreadyEarnedStar() {
        val old = campaign("рекуперация")
        val new = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "рекуперация", 1, 2, listOf(CampaignTask.T1("l1-t1", 3)))),
        )
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре"), setOf("l1-t1"))),
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, new, index)
        assertEquals(setOf("l1-t1"), result.state.levels.getValue("рекуперация").completedTaskIds)
        assertTrue(result.changes.any { it.kind == ReconciliationChangeKind.TASK_STARS_PRESERVED })
    }

    @Test fun changedTaskListKeepsStarsAndAddsTasksSatisfiedByFound() {
        val old = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "рекуперация", 1, 1, listOf(
                CampaignTask.T1("l1-t1", 1),
                CampaignTask.T6("l1-t6", "cat", 2, listOf("каре", "река")),
            ))),
        )
        val new = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "рекуперация", 1, 1, listOf(
                CampaignTask.T1("l1-t1", 1),
                CampaignTask.T6("l1-t6", "cat", 2, listOf("каре")),
                CampaignTask.T2("l1-t2", 'к', 1),
            ))),
        )
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре", "река"), setOf("l1-t1", "l1-t6"))),
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, new, index)
        assertEquals(setOf("l1-t1", "l1-t6", "l1-t2"),
            result.state.levels.getValue("рекуперация").completedTaskIds)
        assertTrue(result.changes.any {
            it.kind == ReconciliationChangeKind.TASK_COMPLETED_FROM_FOUND && it.detail == "l1-t2"
        })
    }

    @Test fun removedTaskLosesItsStarAndIsNotTransferred() {
        val old = campaign("рекуперация")
        val new = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "рекуперация", 1, 1, listOf(CampaignTask.T1("l1-t1", 1)))),
        )
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре"), setOf("l1-t1", "removed"))),
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, new, index)
        assertEquals(setOf("l1-t1"), result.state.levels.getValue("рекуперация").completedTaskIds)
        assertTrue(result.changes.any { it.kind == ReconciliationChangeKind.TASK_REMOVED && it.detail == "removed" })
    }

    @Test fun changedLevelWordResetsOnlyThatLevel() {
        val old = campaign("рекуперация", "опровержение")
        val new = campaign("рекуперация", "хлопкопункт")
        val saved = CampaignProgress(
            levels = mapOf(
                "рекуперация" to levelProgress(listOf("каре"), setOf("l1-t1")),
                "опровержение" to levelProgress(listOf("перо"), setOf("l2-t1")),
            ),
            highestUnlockedLevel = 2,
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, new, index)
        assertTrue(result.isCompatible)
        assertEquals(setOf("l1-t1"), result.state.levels.getValue("рекуперация").completedTaskIds)
        assertNull(result.state.levels["опровержение"])
        assertEquals(listOf("опровержение"), result.resetLevels.map { it.word })
        assertEquals(2, result.state.highestUnlockedLevel)
    }

    @Test fun swappedLevelsKeepProgressByWord() {
        fun withWordTasks(first: String, second: String) = Campaign(
            listOf(Stage(1, 1, 2)),
            listOf(
                CampaignLevel(1, first, 1, 1, listOf(CampaignTask.T1("task-$first", 1))),
                CampaignLevel(2, second, 1, 1, listOf(CampaignTask.T1("task-$second", 1))),
            ),
        )
        val old = withWordTasks("рекуперация", "опровержение")
        val new = withWordTasks("опровержение", "рекуперация")
        assertEquals(CampaignIdentity.of(old), CampaignIdentity.of(new))
        val saved = CampaignProgress(
            levels = mapOf(
                "рекуперация" to levelProgress(listOf("каре"), setOf("task-рекуперация")),
                "опровержение" to levelProgress(listOf("перо"), setOf("task-опровержение")),
            ),
            highestUnlockedLevel = 2,
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, new, index)
        assertEquals(setOf("task-рекуперация"),
            result.state.levels.getValue("рекуперация").completedTaskIds)
        assertEquals(setOf("task-опровержение"),
            result.state.levels.getValue("опровержение").completedTaskIds)
        assertEquals(2, result.state.highestUnlockedLevel)
    }

    @Test fun frontierNeverDecreases() {
        val campaign = campaign("рекуперация", "опровержение", "хлопкопункт")
        val saved = CampaignProgress(highestUnlockedLevel = 3, campaignId = "old")
        val result = CampaignProgressReconciler.reconcile(saved, campaign, index)
        assertEquals(3, result.state.highestUnlockedLevel)
    }

    @Test fun completelyDifferentWordsAreIncompatibleAndNotGuessed() {
        val campaign = campaign("рекуперация")
        val saved = CampaignProgress(
            levels = mapOf("чужое" to levelProgress(listOf("кот"), setOf("old"))),
            highestUnlockedLevel = 5,
            finaleReached = true,
            campaignId = "old-30",
        )
        val result = CampaignProgressReconciler.reconcile(saved, campaign, index)
        assertFalse(result.isCompatible)
        assertEquals("no-matching-level-words", result.incompatibleReason)
        assertTrue(result.state.levels.isEmpty())
        assertEquals(1, result.state.highestUnlockedLevel)
    }

    @Test fun reusedTaskIdWithDifferentSemanticsDoesNotKeepOldStar() {
        val old = campaign("рекуперация")
        val new = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "рекуперация", 1, 1, listOf(CampaignTask.T1("l1-t1", 5)))),
        )
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре"), setOf("l1-t1"))),
            campaignId = CampaignIdentity.of(old),
        )
        val withoutHistory = CampaignProgressReconciler.reconcile(saved, new, index)
        assertTrue("l1-t1" in withoutHistory.state.levels.getValue("рекуперация").completedTaskIds)

        val withHistory = CampaignProgressReconciler.reconcile(saved, new, index, previousCampaign = old)
        assertFalse("l1-t1" in withHistory.state.levels.getValue("рекуперация").completedTaskIds)
        assertTrue(withHistory.changes.any { it.kind == ReconciliationChangeKind.TASK_SEMANTICS_CHANGED })
    }

    @Test fun reusedIdWithAnotherTaskTypeIsRejectedEvenWithoutPreviousCampaign() {
        val old = campaign("рекуперация")
        // ID l1-t2 раньше принадлежал T2, теперь это T1 с другим условием.
        val new = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, "рекуперация", 1, 1, listOf(CampaignTask.T1("l1-t2", 1)))),
        )
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(emptyList(), setOf("l1-t2"))),
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, new, index)
        assertFalse("l1-t2" in result.state.levels.getValue("рекуперация").completedTaskIds)
        assertTrue(result.changes.any {
            it.kind == ReconciliationChangeKind.TASK_SEMANTICS_CHANGED && it.detail == "l1-t2:T1"
        })
    }

    @Test fun hintLadderForMissingTargetResetsButPurchasesStay() {
        val old = campaign("рекуперация")
        val hints = HintState(HintLadder(HintTarget("исчезнувшее", "l1-t1", TaskType.T1)), shownSteps = 4, paidUnits = 2)
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре"), setOf("l1-t1"), hints)),
            campaignId = CampaignIdentity.of(old),
        )
        val result = CampaignProgressReconciler.reconcile(saved, old, index)
        val restored = result.state.levels.getValue("рекуперация").hints
        assertNull(restored.ladder.target)
        assertEquals(4, restored.shownSteps)
        assertEquals(2, restored.paidUnits)
        assertTrue(result.changes.any { it.kind == ReconciliationChangeKind.HINT_LADDER_RESET })
    }

    @Test fun invalidFoundWordsAreDroppedButValidOnesStay() {
        val campaign = campaign("рекуперация")
        val saved = CampaignProgress(
            levels = mapOf("рекуперация" to levelProgress(listOf("каре", "чужое"), setOf("l1-t1"))),
            campaignId = CampaignIdentity.of(campaign),
        )
        val result = CampaignProgressReconciler.reconcile(saved, campaign, index)
        assertEquals(listOf("каре"), result.state.levels.getValue("рекуперация").found)
        assertTrue(result.changes.any { it.kind == ReconciliationChangeKind.FOUND_WORDS_FILTERED })
    }

    @Test fun excludedDictionaryWordsAreRemovedButStarsStay() {
        // Both excluded words were formable and accepted in the previous dictionary.
        val source = "печенькаибо"
        val campaign = campaign(source)
        val oldIndex = WordIndex(TestData.index.words + listOf("печенька", "ибо"))
        var oldGame = GameState.start(source, oldIndex)
        for (word in listOf("печенька", "ибо")) {
            val submission = oldGame.submit(word, oldIndex)
            assertEquals(AnswerResult.CORRECT, submission.result)
            oldGame = submission.state
        }
        val saved = CampaignProgress(
            levels = mapOf(source to levelProgress(oldGame.found, setOf("l1-t1"))),
            campaignId = CampaignIdentity.of(campaign),
        )
        val result = CampaignProgressReconciler.reconcile(saved, campaign, TestData.index)
        assertTrue(result.isCompatible)
        assertEquals(emptyList(), result.state.levels.getValue(source).found)
        assertEquals(setOf("l1-t1"), result.state.levels.getValue(source).completedTaskIds)
        assertTrue(result.changes.any { it.kind == ReconciliationChangeKind.FOUND_WORDS_FILTERED })
    }

    @Test fun retroactiveAchievementsAreGrantedByFoundWordsAndStarsDuringReconciliation() {
        val source = "абвгдежзий"
        val richWords = listOf("абвгд", "бвгд", "абвгдеж", "бвгдежзи", "вгдежзий", "абвгдежзи")
        val richIndex = WordIndex(richWords)
        val campaignWithOptionalTasks = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, source, 1, 1, listOf(
                CampaignTask.T1("l1-t1", 1), CampaignTask.T1("l1-t2", 1)))),
        )
        val saved = CampaignProgress(
            levels = mapOf(source to levelProgress(richWords, setOf("l1-t1", "l1-t2"))),
            campaignId = "old",
        )
        val result = CampaignProgressReconciler.reconcile(saved, campaignWithOptionalTasks, richIndex)
        val savedLevel = result.state.levels.getValue(source)
        assertTrue(savedLevel.achievements.containsAll(LevelAchievement.entries))
        assertTrue(result.changes.any {
            it.kind == ReconciliationChangeKind.ACHIEVEMENTS_GRANTED && it.levelWord == source &&
                it.detail?.contains(LevelAchievement.GIANT.name) == true
        })
        // Решение Дано: достижение не меняет звёзды, границу и счётчики подсказок.
        assertEquals(2, CampaignRules.stars(campaignWithOptionalTasks.levels.first(), result.state))
        assertEquals(1, result.state.highestUnlockedLevel)
        assertEquals(0, savedLevel.hints.shownSteps)
    }

    @Test fun retroactiveGrantIsIdempotentAndSilentOnRepeat() {
        val source = "рекуперация"
        val campaign = Campaign(
            listOf(Stage(1, 1, 1)),
            listOf(CampaignLevel(1, source, 1, 1, listOf(CampaignTask.T1("l1-t1", 1)))),
        )
        val saved = CampaignProgress(levels = mapOf(source to levelProgress(
            listOf("каре", "река"), setOf("l1-t1"))), campaignId = "old")
        val first = CampaignProgressReconciler.reconcile(saved, campaign, index)
        assertTrue(first.changes.any { it.kind == ReconciliationChangeKind.ACHIEVEMENTS_GRANTED })
        val second = CampaignProgressReconciler.reconcile(first.state, campaign, index)
        assertEquals(first.state, second.state)
        assertFalse(second.changes.any { it.kind == ReconciliationChangeKind.ACHIEVEMENTS_GRANTED })
    }

    @Test fun earnedAchievementsSurviveThresholdDictionaryAndOtherLevelWordChanges() {
        val firstWord = "рекуперация"
        val secondWord = "опровержение"
        val old = campaign(firstWord, secondWord)
        val new = Campaign(
            listOf(Stage(1, 1, 2)),
            listOf(
                CampaignLevel(1, firstWord, 1, 1, listOf(CampaignTask.T1("l1-t1", 4))),
                CampaignLevel(2, "хлопкопункт", 1, 1, listOf(CampaignTask.T1("l2-t1", 1))),
            ),
        )
        val saved = CampaignProgress(levels = mapOf(
            firstWord to levelProgress(listOf("каре"), setOf("l1-t1")).copy(
                achievements = setOf(LevelAchievement.EXPERT, LevelAchievement.GIANT, LevelAchievement.NO_HINTS),
                hintsUsed = true,
            ),
            secondWord to levelProgress(listOf("перо"), setOf("l2-t1")).copy(
                achievements = setOf(LevelAchievement.COLLECTOR),
            ),
        ), highestUnlockedLevel = 2, campaignId = CampaignIdentity.of(old))
        val result = CampaignProgressReconciler.reconcile(saved, new, index, previousCampaign = old)
        val firstLevel = result.state.levels.getValue(firstWord)
        assertEquals(setOf(LevelAchievement.EXPERT, LevelAchievement.GIANT, LevelAchievement.NO_HINTS),
            firstLevel.achievements)
        assertTrue(firstLevel.hintsUsed)
        assertFalse(result.changes.any {
            it.kind == ReconciliationChangeKind.ACHIEVEMENTS_GRANTED && it.levelWord == firstWord
        })
        // Смена исходного слова другого уровня архивирует только его прогресс.
        assertEquals(listOf(secondWord), result.resetLevels.map { it.word })
        assertNull(result.state.levels[secondWord])
        assertEquals(setOf(LevelAchievement.COLLECTOR), result.state.levels.getValue("хлопкопункт").achievements)
    }

    @Test fun hintsUsedIsDerivedForOldSavesWithoutTheField() {
        val source = "рекуперация"
        val campaign = campaign(source)
        fun reconciled(hints: HintState) = CampaignProgressReconciler.reconcile(
            CampaignProgress(levels = mapOf(source to levelProgress(listOf("каре"), emptySet(), hints))),
            campaign, index,
        )
        assertTrue(reconciled(HintState(shownSteps = 2)).state.levels.getValue(source).hintsUsed)
        assertTrue(reconciled(HintState(paidUnits = 1)).state.levels.getValue(source).hintsUsed)
        assertFalse(reconciled(HintState()).state.levels.getValue(source).hintsUsed)
    }

    @Test fun achievementsSurviveSourceReplacementAndRestartWithNoPreviousCampaign() {
        val old = campaign("рекуперация", "опровержение")
        val earned = setOf(LevelAchievement.EXPERT, LevelAchievement.GIANT)
        val seed = CampaignProgress(levels = mapOf(
            "рекуперация" to CampaignLevelProgress(achievements = earned, hintsUsed = true),
            "опровержение" to CampaignLevelProgress(achievements = setOf(LevelAchievement.COLLECTOR)),
        ), highestUnlockedLevel = 2)
        val saved = CampaignProgressReconciler.reconcile(seed, old, index).state
        val changed = campaign("хлопкопункт", "опровержение")
        val first = CampaignProgressReconciler.reconcile(saved, changed, index)
        assertTrue(first.isCompatible)
        val replacement = first.state.levels.getValue("хлопкопункт")
        assertEquals(earned, replacement.achievements)
        assertTrue(replacement.hintsUsed)
        assertTrue(replacement.found.isEmpty())
        assertTrue(replacement.completedTaskIds.isEmpty())
        assertEquals(saved.levels.getValue("опровержение"), first.state.levels.getValue("опровержение"))
        assertFalse(first.changes.any { it.kind == ReconciliationChangeKind.ACHIEVEMENTS_GRANTED })
        assertEquals(first.state, CampaignProgressReconciler.reconcile(first.state, changed, index).state)

        // Даже полная замена слов не отнимает достижения и не восстанавливает звёзды.
        val replacedAll = campaign("хлопкопункт", "селитроварня")
        val fullReplacement = CampaignProgressReconciler.reconcile(saved, replacedAll, index)
        assertTrue(fullReplacement.isCompatible)
        assertEquals(earned, fullReplacement.state.levels.getValue("хлопкопункт").achievements)
        assertEquals(setOf(LevelAchievement.COLLECTOR),
            fullReplacement.state.levels.getValue("селитроварня").achievements)
    }

    @Test fun sourceReplacementWithKnownHistoryRetainsAchievementsButNotAnswers() {
        val old = campaign("рекуперация")
        val saved = CampaignProgress(levels = mapOf("рекуперация" to levelProgress(
            listOf("каре"), setOf("l1-t1")).copy(achievements = setOf(LevelAchievement.EXPERT))))
        val result = CampaignProgressReconciler.reconcile(saved, campaign("хлопкопункт"), index,
            previousCampaign = old)
        assertTrue(result.isCompatible)
        val replacement = result.state.levels.getValue("хлопкопункт")
        assertEquals(setOf(LevelAchievement.EXPERT), replacement.achievements)
        assertTrue(replacement.found.isEmpty())
        assertTrue(replacement.completedTaskIds.isEmpty())
    }

    @Test fun unaddressableAchievementsRemainStoredWithoutAffectingOtherLevels() {
        val target = campaign("рекуперация")
        val orphan = CampaignLevelProgress(achievements = setOf(LevelAchievement.GIANT), hintsUsed = true)
        val result = CampaignProgressReconciler.reconcile(
            CampaignProgress(levels = mapOf("прежнее" to orphan.copy(found = listOf("кот")))), target, index)
        assertEquals(listOf("прежнее"), result.resetLevels.map { it.word })
        assertEquals(orphan, result.state.levels.getValue("прежнее"))
        assertNull(result.state.levels["рекуперация"])
        assertEquals(0, CampaignRules.summary(target, result.state).stars)
        val again = CampaignProgressReconciler.reconcile(result.state, target, index)
        assertEquals(result.state, again.state)
        assertTrue(again.resetLevels.isEmpty())
    }

    @Test fun numberAddressedLevelWithoutHistoryIsArchivedNotGuessed() {
        val campaign = campaign("рекуперация")
        val saved = CampaignProgress(
            levels = mapOf(
                "1" to levelProgress(listOf("каре"), setOf("l1-t1")),
                "рекуперация" to levelProgress(listOf("река"), setOf("l1-t1")),
            ),
            campaignId = "old",
        )
        val result = CampaignProgressReconciler.reconcile(saved, campaign, index)
        assertTrue(result.resetLevels.any { it.word == "1" && it.reason == "number-addressed-level-without-history" })
        assertEquals(listOf("река"), result.state.levels.getValue("рекуперация").found)
    }

    @Test fun numberAddressedLevelUsesOnlyExplicitHistoricalMapping() {
        val campaign = campaign("рекуперация")
        val saved = CampaignProgress(
            levels = mapOf("1" to levelProgress(listOf("каре"), setOf("l1-t1"))),
            campaignId = "old",
        )
        val result = CampaignProgressReconciler.reconcile(
            saved, campaign, index, historicalLevelWords = mapOf(1 to "рекуперация"),
        )
        assertEquals(setOf("l1-t1"), result.state.levels.getValue("рекуперация").completedTaskIds)
    }
}
