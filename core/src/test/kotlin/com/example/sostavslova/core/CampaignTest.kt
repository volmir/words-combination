package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** Синтетический short15: числа зеркалят tools/campaign_profiles.json, словарь не нужен. */
    private fun short15(): Campaign {
        val base = "картотека"
        val sources = Alphabet.LETTERS.take(15).map { base + it }
        val t1 = listOf(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24)
        val t2 = listOf(3, 3, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7)
        val t3 = listOf(6, 6, 6, 7, 7, 7, 8, 8, 8)
        val t5 = listOf(5, 5, 6, 7, 7)
        val t3Targets = listOf("картет", "ракета", "карета", "картека", "артотек", "картоет",
            "картотек", "артотека", "картоетка")
        val t4Targets = listOf("кот", "ток", "рак", "карта", "катер", "тетка",
            "тара", "тора", "кара", "крот", "актер", "кора")
        val t6Words = listOf("рот", "рок", "кот", "ток", "рак")
        val endings = listOf("ка", "та", "ра", "ок", "ет")
        val rows = listOf(
            (1..3) to (listOf(TaskType.T1) to 1),
            (4..5) to (listOf(TaskType.T1, TaskType.T2) to 2),
            (6..8) to (listOf(TaskType.T1, TaskType.T2, TaskType.T3) to 3),
            (9..10) to (listOf(TaskType.T1, TaskType.T2, TaskType.T3, TaskType.T4) to 3),
            (11..13) to (listOf(TaskType.T1, TaskType.T2, TaskType.T3, TaskType.T4, TaskType.T5) to 4),
            (14..14) to (listOf(TaskType.T1, TaskType.T2, TaskType.T4, TaskType.T5, TaskType.T6) to 4),
            (15..15) to (listOf(TaskType.T1, TaskType.T2, TaskType.T3, TaskType.T5, TaskType.T7) to 4),
        )
        var t3Index = 0
        var t4Index = 0
        var t5Index = 0
        val levels = (1..15).map { number ->
            val row = rows.single { number in it.first }
            val stage = listOf(1..3, 4..5, 6..8, 9..10, 11..13, 14..15)
                .indexOfFirst { number in it } + 1
            val tasks = row.second.first.map { type ->
                val id = "$number-$type"
                when (type) {
                    TaskType.T1 -> CampaignTask.T1(id, t1[number - 1])
                    TaskType.T2 -> CampaignTask.T2(id, 'к', t2[number - 4])
                    TaskType.T3 -> CampaignTask.T3(id, t3[t3Index], t3Targets[t3Index++])
                    TaskType.T4 -> {
                        val count = when (stage) { 4 -> 1; 5 -> 2; else -> 3 }
                        CampaignTask.T4(id, (0 until count).map { t4Targets[t4Index++] })
                    }
                    TaskType.T5 -> CampaignTask.T5(id, endings[t5Index], t5[t5Index++])
                    TaskType.T6 -> CampaignTask.T6(id, "professions", 4, t6Words)
                    TaskType.T7 -> CampaignTask.T7(id, 'к', 8)
                }
            }
            CampaignLevel(number, sources[number - 1], stage, row.second.second, tasks)
        }
        val stages = listOf(1..3, 4..5, 6..8, 9..10, 11..13, 14..15)
            .mapIndexed { index, range -> Stage(index + 1, range.first, range.last) }
        return Campaign(stages, levels)
    }

    @Test fun short15ProfileIsExplicitAndRequiresAllSixStagesAndTypes() {
        val campaign = short15()
        assertEquals(emptyList(), ProductionCampaignValidator.validate(
            campaign, ProductionCampaignProfile.SHORT15_RELEASE_V1))
        assertEquals(15, campaign.levels.size)
        assertEquals(49, campaign.maxStars)
        assertEquals(listOf(3, 2, 3, 2, 3, 2), campaign.stages.map { it.lastLevel - it.firstLevel + 1 })
        assertEquals(TaskType.entries.toSet(), campaign.levels.flatMap { it.tasks }.map { it.type }.toSet())
        assertTrue(campaign.levels[13].tasks.any { it.type == TaskType.T6 })
        assertTrue(campaign.levels[14].tasks.any { it.type == TaskType.T7 })
        assertEquals(5, campaign.levels[14].maxStars)
        assertEquals(4, campaign.levels[14].requiredStars)
        assertEquals("short15-release-v1", ProductionCampaignProfile.SHORT15_RELEASE_V1.id)
        assertEquals("release-30", ProductionCampaignProfile.RELEASE_30.id)
    }

    @Test fun short15RejectsFormerAndIncorrectNumericContract() {
        val campaign = short15()
        val oldT1 = listOf(10, 12, 14, 16, 18, 20, 22, 25, 27, 29, 31, 33, 35, 37, 40)
        val oldT2 = listOf(5, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        for (level in campaign.levels) for (task in level.tasks) {
            val wrong: CampaignTask = when (task) {
                is CampaignTask.T1 -> task.copy(n = oldT1[level.number - 1])
                is CampaignTask.T2 -> task.copy(n = oldT2[level.number - 4])
                is CampaignTask.T3 -> task.copy(minLength = task.minLength + 1)
                is CampaignTask.T5 -> task.copy(minLength = task.minLength + 1)
                is CampaignTask.T6 -> task.copy(n = 5)
                is CampaignTask.T7 -> task.copy(n = 10)
                is CampaignTask.T4 -> continue
            }
            val changed = campaign.copy(levels = campaign.levels.map { current ->
                if (current.number == level.number) current.copy(tasks = current.tasks.map {
                    if (it.id == task.id) wrong else it
                }) else current
            })
            assertTrue(Short15CampaignValidator.validate(changed).any {
                it.levelNumber == level.number && it.taskId == task.id
            }, "Former/wrong parameter accepted: ${level.number} ${task.id}")
        }
    }

    @Test fun short15AndLegacy30ProfilesRejectEachOther() {
        assertTrue(ProductionCampaignValidator.validate(short15(),
            ProductionCampaignProfile.RELEASE_30).any { it.field == "levels.size" })
        assertTrue(ProductionCampaignValidator.validate(production(),
            ProductionCampaignProfile.SHORT15_RELEASE_V1).any { it.field == "levels.size" })
        val wrongFinale = short15().copy(levels = short15().levels.dropLast(1) +
            short15().levels.last().copy(requiredStars = 3))
        assertTrue(ProductionCampaignValidator.validate(wrongFinale,
            ProductionCampaignProfile.SHORT15_RELEASE_V1).any { it.levelNumber == 15 && it.field == "requiredStars" })
        val unknown = ProductionCampaignValidator.validate(short15(), "unknown-profile")
        assertTrue(unknown.any { it.field == "profile" })
    }

    @Test fun short15AnyRUnlocksNextAndMasterNeedsAllFortyNine() {
        val campaign = short15()
        val emptyIndex = WordIndex(emptyList())
        fun earn(progress: CampaignProgress, level: CampaignLevel, stars: Int): CampaignProgress =
            CampaignRules.recordLevel(campaign, progress, CurrentCampaignLevel(level,
                GameState.start(level.word, emptyIndex),
                level.tasks.take(stars).map { it.id }.toSet()))

        val passed = campaign.levels.fold(CampaignProgress()) { state, level ->
            earn(state, level, level.requiredStars)
        }
        assertTrue(passed.finaleReached)
        assertEquals(15, passed.highestUnlockedLevel)
        val requiredOnly = CampaignRules.summary(campaign, passed)
        assertEquals(42, requiredOnly.stars)
        assertEquals(49, requiredOnly.maxStars)
        assertTrue(!requiredOnly.isMaster)
        assertEquals(9, CampaignRules.continueLevel(campaign, passed).number)

        val full = campaign.levels.fold(passed) { state, level -> earn(state, level, level.maxStars) }
        val master = CampaignRules.summary(campaign, full)
        assertEquals(49, master.stars)
        assertTrue(master.isMaster)
        assertEquals(15, CampaignRules.continueLevel(campaign, full).number)

        // Любой набор из R=4 заданий уровня 15 завершает финал; пятая звезда — только добор.
        val beforeFinal = campaign.levels.dropLast(1).fold(CampaignProgress()) { state, level ->
            earn(state, level, level.requiredStars)
        }
        val final = campaign.levels.last()
        for (chosen in final.tasks.indices.toList().combinations(4)) {
            val ids = chosen.map { final.tasks[it].id }.toSet()
            val state = CampaignRules.recordLevel(campaign, beforeFinal, CurrentCampaignLevel(
                final, GameState.start(final.word, emptyIndex), ids))
            assertTrue(state.finaleReached)
            assertFalse(CampaignRules.summary(campaign, state).isMaster)
        }
    }

    private fun <T> List<T>.combinations(count: Int): List<List<T>> {
        if (count == 0) return listOf(emptyList())
        if (size < count) return emptyList()
        return drop(1).combinations(count - 1).map { listOf(first()) + it } + drop(1).combinations(count)
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
