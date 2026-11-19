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
