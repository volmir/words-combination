package com.example.sostavslova.core

import java.io.File
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampaignDatasetAuditTest {
    private fun input(): JSONObject = JSONObject(File("../tools/campaign_validation_vectors.json").readText())

    @Test fun syntheticCampaignUsesRuntimeForEveryAnswerAndEveryRSubset() {
        val result = CampaignDatasetAudit.audit(input())
        assertEquals(0, result.getJSONArray("errors").length(), result.toString(2))
        assertEquals(12, result.getInt("r_combinations_checked"))
        assertTrue(result.getBoolean("finale_reached"))
        val levels = result.getJSONArray("levels")
        val first = levels.getJSONObject(0)
        val answers = first.getJSONArray("answers").toList()
        assertTrue("картотека" !in answers && "токток" !in answers && "тетет" !in answers)
        val tasks = first.getJSONArray("tasks")
        assertTrue("кот" !in tasks.getJSONObject(0).getJSONArray("accepted").toList())
        assertTrue("кот" !in tasks.getJSONObject(1).getJSONArray("accepted").toList())
        assertEquals(listOf("кара", "тара", "тора"), tasks.getJSONObject(4).getJSONArray("accepted").toList())
        assertTrue("тетка" in tasks.getJSONObject(2).getJSONArray("accepted").toList())
        assertTrue("рак" in levels.getJSONObject(1).getJSONArray("tasks").getJSONObject(1).getJSONArray("accepted").toList())
        assertEquals("*** — *** предмет для хранения небольших вещей.", result.getJSONObject("hints").getString("карта"))
        assertEquals(1, levels.getJSONObject(1).getJSONArray("tasks").getJSONObject(1)
            .getJSONObject("singleton_progress").getInt("тара"))
    }

    @Test fun individuallyImpossibleTaskFailsWithLevelTaskAndReasonEvenIfRIsPossible() {
        val input = input()
        input.getJSONObject("campaign").getJSONArray("levels").getJSONObject(0)
            .getJSONArray("tasks").getJSONObject(1).put("letter", "я")
        val errors = CampaignDatasetAudit.audit(input).getJSONArray("errors")
        assertTrue((0 until errors.length()).map { errors.getJSONObject(it) }.any {
            it.optInt("level") == 1 && it.optString("task") == "initial" &&
                it.getString("reason").contains("individually unreachable")
        }, errors.toString(2))
    }

    @Test fun badT4HintIsRejectedAndProductionModeRequiresFullSchedule() {
        val input = input()
        input.getJSONObject("hintInputs").getJSONObject("карта").put("definitions", listOf("карта"))
        val errors = CampaignDatasetAudit.audit(input).getJSONArray("errors")
        assertTrue((0 until errors.length()).map { errors.getJSONObject(it) }.any {
            it.optInt("level") == 1 && it.optString("task") == "riddle" &&
                it.getString("reason").contains("T4")
        }, errors.toString(2))
        input.put("production", true)
        val productionErrors = CampaignDatasetAudit.audit(input).getJSONArray("errors")
        assertTrue(productionErrors.toString().contains("Expected 30 levels"))
    }

    @Test fun malformedAndForbiddenAssignmentsFailWithAddress() {
        for (word in listOf("токток", "картотека", "праймериз")) {
            val input = input()
            input.getJSONObject("campaign").getJSONArray("levels").getJSONObject(0)
                .getJSONArray("tasks").getJSONObject(3).put("targets", listOf(word))
            val errors = CampaignDatasetAudit.audit(input).getJSONArray("errors")
            assertTrue((0 until errors.length()).map { errors.getJSONObject(it) }.any {
                it.optInt("level") == 1 && it.optString("task") == "riddle" && it.getString("reason").isNotBlank()
            }, errors.toString(2))
        }
    }

    @Test fun repeatedLettersNormalizationAndT7WordCountingMatchVectors() {
        val dictionary = WordIndex(listOf("тётка", "тетет", "токток", "кот", "крот", "картотека").map(Alphabet::normalize))
        val level = CampaignLevel(1, "картотека", 1, 1, listOf(CampaignTask.T7("repeat", 'т', 3)))
        val current = CurrentCampaignLevel(level, GameState.start(level.word, dictionary))
        assertEquals(listOf("кот", "крот", "тетка"), current.game.allWords.sorted())
        val found = TaskRules.submit(current, "ТЁТКА", 4).evaluation
        assertEquals(1, found.progress.single().current)
        assertTrue(!found.progress.single().completed)
    }

    @Test fun thirtyLevelTestCampaignGivesOneHundredStarsAndAnyRLeadsToFinale() {
        // Synthetic dictionary: no article text, category assumptions or generator flags.
        val words = (3..4).flatMap { size ->
            (0 until (if (size == 3) 27 else 81)).map { code ->
                var value = code
                buildString { repeat(size) { append("абв"[value % 3]); value /= 3 } }
            }
        }.filter { word -> word.groupingBy { it }.eachCount().values.all { it <= 3 } }.sorted()
        val targets = words.filter { it.length == 4 }.iterator()
        val endings = words.filter { it.length == 3 }.iterator()
        val rotation = listOf("12346", "12357", "12456", "13457", "23456")
        val hints = JSONObject()
        val levels = (1..30).map { number ->
            val stage = (number - 1) / 5 + 1
            val kinds = if (stage < 6) (1..stage).toList() else rotation[number - 26].map { it.digitToInt() }
            val tasks = kinds.map { type ->
                val task = JSONObject().put("id", "$number-$type").put("type", "T$type")
                when (type) {
                    1 -> task.put("n", 1)
                    2 -> task.put("letter", "а").put("n", 1)
                    3, 4 -> {
                        val word = targets.next()
                        if (type == 3) task.put("minLength", 4).put("hintTarget", word)
                        else {
                            task.put("targets", listOf(word))
                            hints.put(word, JSONObject().put("forms", listOf(word)).put("related", emptyList<String>())
                                .put("definitions", listOf("Предмет для хранения небольших вещей.")))
                        }
                    }
                    5 -> task.put("ending", endings.next()).put("minLength", 3)
                    6 -> task.put("category", "synthetic-$number").put("n", 1).put("words", words)
                    7 -> task.put("letter", "а").put("n", 1)
                }
                task
            }
            JSONObject().put("number", number).put("stage", stage).put("word", "ааабббввв" + Alphabet.LETTERS[number - 1])
                .put("requiredStars", if (number == 30) 4 else listOf(1, 1, 2, 2, 3, 3)[stage - 1]).put("tasks", tasks)
        }
        val stages = (1..6).map { JSONObject().put("number", it).put("firstLevel", it * 5 - 4).put("lastLevel", it * 5) }
        val campaign = JSONObject().put("version", 2).put("countMinLength", 4).put("stages", stages).put("levels", levels)
        val result = CampaignDatasetAudit.audit(JSONObject().put("campaign", campaign).put("dictionary", words)
            .put("production", true).put("hintInputs", hints))
        assertEquals(0, result.getJSONArray("errors").length(), result.toString(2))
        assertEquals(155, result.getInt("r_combinations_checked"))
        assertEquals(100, result.getInt("all_answer_stars"))
        assertTrue(result.getBoolean("finale_reached"))
    }
}
