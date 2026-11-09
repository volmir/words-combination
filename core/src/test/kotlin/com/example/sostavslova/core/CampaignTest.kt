package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampaignTest {
    private val first = CampaignLevel(1, "электростанция", 1, 2, listOf(
        CampaignTask.T1("l1-count", 3),
        CampaignTask.T2("l1-initial", 'т', 1),
        CampaignTask.T3("l1-length", 4, "торт"),
        CampaignTask.T4("l1-riddles", listOf("кот", "крот")),
    ))
    private val second = CampaignLevel(2, "растительность", 1, 2, listOf(
        CampaignTask.T5("l2-ending", "ть", 4),
        CampaignTask.T6("l2-theme", "animals", 1, listOf("лось", "линь")),
        CampaignTask.T7("l2-letter", 'р', 2),
    ))
    private fun mini() = Campaign(stages = listOf(Stage(1, 1, 2)), levels = listOf(first, second))

    private fun error(campaign: Campaign, field: String, level: Int? = null, task: String? = null) {
        val errors = CampaignValidator.validate(campaign)
        assertTrue(errors.any { it.field == field && it.levelNumber == level && it.taskId == task },
            "Expected $level/$task/$field, got $errors")
        if (task != null) {
            val diagnostic = errors.first { it.field == field && it.taskId == task }.toString()
            assertTrue(diagnostic.contains("level=$level"))
            assertTrue(diagnostic.contains("task=$task"))
        }
    }

    private fun withTask(task: CampaignTask) = mini().copy(levels = listOf(first.copy(tasks = listOf(task), requiredStars = 1)))

    @Test fun allSevenTypesAndCurrentLevelData() {
        assertEquals(emptyList(), CampaignValidator.validate(mini()))
        assertEquals(4, first.maxStars)
        assertEquals(TaskType.entries.toSet(), mini().levels.flatMap { it.tasks }.map { it.type }.toSet())
        val current = CurrentCampaignLevel(first, GameState("электростанция", listOf("кот")))
        assertEquals(emptySet(), current.completedTaskIds)
        assertEquals(first.word, current.game.word)
    }

    @Test fun versionAndCountingLengthAreFixed() {
        error(mini().copy(version = 1), "version")
        error(mini().copy(countMinLength = 3), "countMinLength")
    }

    @Test fun emptyCollectionsAndStageReferences() {
        error(mini().copy(stages = emptyList()), "stages")
        error(mini().copy(levels = emptyList()), "levels")
        error(mini().copy(stages = listOf(Stage(1, 2, 1))), "stages.range")
        error(mini().copy(stages = listOf(Stage(1, 1, 2), Stage(1, 1, 2))), "stages.number")
        error(mini().copy(levels = listOf(first.copy(stage = 7))), "stage", 1)
        error(mini().copy(levels = listOf(first.copy(number = 3))), "stage", 3)
    }

    @Test fun levelKeysNumbersAndOrder() {
        error(mini().copy(levels = listOf(first, second.copy(number = 1))), "number", 1)
        error(mini().copy(levels = listOf(first, second.copy(word = first.word))), "word", 2)
        error(mini().copy(levels = listOf(second, first)), "number", 1)
        for (word in listOf("", "Кот", "ёлка", "кот!", "abс", "я".repeat(31))) {
            error(mini().copy(levels = listOf(first.copy(word = word))), "word", 1)
        }
    }

    @Test fun taskCountsThresholdsIdsAndTypes() {
        error(mini().copy(levels = listOf(first.copy(tasks = emptyList()))), "tasks", 1)
        error(mini().copy(levels = listOf(first.copy(tasks = first.tasks + second.tasks))), "tasks", 1)
        for (r in listOf(0, 5)) error(mini().copy(levels = listOf(first.copy(requiredStars = r))), "requiredStars", 1)
        error(withTask(CampaignTask.T1("", 1)), "id", 1, "")
        error(mini().copy(levels = listOf(first, second.copy(tasks = listOf(CampaignTask.T7("l1-count", 'р', 1))))), "id", 2, "l1-count")
        error(mini().copy(levels = listOf(first.copy(tasks = listOf(CampaignTask.T1("a", 1), CampaignTask.T1("b", 2))))), "type", 1, "b")
    }

    @Test fun invalidParametersHaveLevelAndTaskAddress() {
        val cases = listOf(
            CampaignTask.T1("bad", 0) to "n",
            CampaignTask.T2("bad", 'ё', 1) to "letter",
            CampaignTask.T2("bad", 'т', -1) to "n",
            CampaignTask.T3("bad", 2, "кот") to "minLength",
            CampaignTask.T3("bad", 5, "кот") to "hintTarget",
            CampaignTask.T3("bad", 3, "Кот") to "hintTarget",
            CampaignTask.T3("bad", 3, "тттт") to "hintTarget",
            CampaignTask.T3("bad", 3, first.word) to "hintTarget",
            CampaignTask.T4("bad", emptyList()) to "targets",
            CampaignTask.T4("bad", listOf("кот", "кот")) to "targets",
            CampaignTask.T4("bad", listOf("ёж")) to "targets",
            CampaignTask.T5("bad", "", 3) to "ending",
            CampaignTask.T5("bad", "Ё", 3) to "ending",
            CampaignTask.T5("bad", "а", 31) to "minLength",
            CampaignTask.T6("bad", "", 1, listOf("кот")) to "category",
            CampaignTask.T6("bad", "animals", 0, listOf("кот")) to "n",
            CampaignTask.T6("bad", "animals", 1, emptyList()) to "words",
            CampaignTask.T6("bad", "animals", 2, listOf("кот")) to "n",
            CampaignTask.T6("bad", "animals", 1, listOf("кот", "кот")) to "words",
            CampaignTask.T6("bad", "animals", 1, listOf("слонн")) to "words",
            CampaignTask.T7("bad", 'A', 1) to "letter",
            CampaignTask.T7("bad", 'т', 0) to "n",
        )
        for ((task, field) in cases) error(withTask(task), field, 1, "bad")
    }

    @Test fun assignmentsAreUniqueAcrossCampaignButLettersAndAnswersMayRepeat() {
        val other = first.copy(number = 2, word = "картотека", tasks = listOf(CampaignTask.T4("duplicate", listOf("торт"))))
        error(mini().copy(levels = listOf(first, other)), "targets", 2, "duplicate")
        for ((original, duplicate, field) in listOf(
            Triple(CampaignTask.T5("a", "а", 3), CampaignTask.T5("b", "а", 4), "ending"),
            Triple(CampaignTask.T6("a", "animals", 1, listOf("кот")), CampaignTask.T6("b", "animals", 1, listOf("кот")), "category"),
        )) {
            error(mini().copy(levels = listOf(first.copy(tasks = listOf(original), requiredStars = 1),
                other.copy(tasks = listOf(duplicate), requiredStars = 1))), field, 2, "b")
        }
        val repeatedLetters = mini().copy(levels = listOf(
            first.copy(tasks = listOf(CampaignTask.T7("a", 'т', 1)), requiredStars = 1),
            other.copy(tasks = listOf(CampaignTask.T7("b", 'т', 1)), requiredStars = 1),
        ))
        assertEquals(emptyList(), CampaignValidator.validate(repeatedLetters))
    }

    @Test fun productionValidationIsSeparate() {
        assertEquals(emptyList(), CampaignValidator.validate(mini()))
        val fields = ProductionCampaignValidator.validate(mini()).map { it.field }
        assertTrue("levels.size" in fields)
        assertTrue("maxStars" in fields)
        assertTrue("stages.size" in fields)
    }

    /** Синтетические слова проверяют структуру, не заменяя аудит словаря генератором. */
    private fun production(): Campaign {
        val source = "электростанция"
        val candidates = source.flatMap { a -> source.map { b -> "а$a$b" } }.distinct()
        val targets = WordIndex(candidates).findBuildable(source).iterator()
        val stages = (1..6).map { Stage(it, (it - 1) * 5 + 1, it * 5) }
        val levels = (1..30).map { number ->
            val stage = (number - 1) / 5 + 1
            val types = when (number) {
                26 -> listOf(TaskType.T1, TaskType.T2, TaskType.T3, TaskType.T4, TaskType.T6)
                27 -> listOf(TaskType.T1, TaskType.T2, TaskType.T3, TaskType.T5, TaskType.T7)
                else -> TaskType.entries.take(minOf(stage, 5))
            }
            val tasks = types.map { type ->
                val id = "$number-$type"
                when (type) {
                    TaskType.T1 -> CampaignTask.T1(id, 1)
                    TaskType.T2 -> CampaignTask.T2(id, 'т', 1)
                    TaskType.T3 -> CampaignTask.T3(id, 3, targets.next())
                    TaskType.T4 -> CampaignTask.T4(id, listOf(targets.next()))
                    TaskType.T5 -> CampaignTask.T5(id, "а${Alphabet.LETTERS[number - 1]}", 3)
                    TaskType.T6 -> CampaignTask.T6(id, "animals", 1, listOf("кот"))
                    TaskType.T7 -> CampaignTask.T7(id, 'т', 1)
                }
            }
            CampaignLevel(number, source + Alphabet.LETTERS[number - 1], stage,
                if (number == 30) 4 else listOf(1, 1, 2, 2, 3, 3)[stage - 1], tasks)
        }
        return Campaign(stages, levels)
    }

    @Test fun productionScheduleAndItsViolations() {
        val campaign = production()
        assertEquals(emptyList(), ProductionCampaignValidator.validate(campaign))
        assertEquals(100, campaign.maxStars)
        val last = campaign.levels.last()
        val errors = ProductionCampaignValidator.validate(campaign.copy(levels = campaign.levels.dropLast(1) + last.copy(requiredStars = 3)))
        assertTrue(errors.any { it.levelNumber == 30 && it.field == "requiredStars" })
        val noThemes = campaign.copy(levels = campaign.levels.map { level ->
            if (level.number == 26) level.copy(tasks = level.tasks.dropLast(1) + CampaignTask.T5("replacement", "яя", 3)) else level
        })
        assertTrue(ProductionCampaignValidator.validate(noThemes).any { it.field == "tasks.types" })
        val wrongType = campaign.copy(levels = listOf(campaign.levels.first().copy(tasks = listOf(CampaignTask.T7("wrong", 'т', 1)))) + campaign.levels.drop(1))
        assertTrue(ProductionCampaignValidator.validate(wrongType).any { it.levelNumber == 1 && it.taskId == "wrong" && it.field == "type" })
    }
}
