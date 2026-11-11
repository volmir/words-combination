package com.example.sostavslova.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.system.exitProcess

/** GEN-04 offline entry point. Uses the very same WordIndex/TaskRules/CampaignRules as Android. */
object CampaignDatasetAudit {
    private val forbidden = setOf("прелюбодейка", "брудершафт", "праймериз")
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    internal fun decode(json: JSONObject): Campaign = Campaign(
        version = json.getInt("version"), countMinLength = json.getInt("countMinLength"),
        stages = json.getJSONArray("stages").objects().map {
            Stage(it.getInt("number"), it.getInt("firstLevel"), it.getInt("lastLevel"))
        },
        levels = json.getJSONArray("levels").objects().map { level ->
            val number = level.getInt("number")
            CampaignLevel(number, level.getString("word"), level.getInt("stage"), level.getInt("requiredStars"),
                level.getJSONArray("tasks").objects().map { task ->
                    val id = task.getString("id")
                    try {
                        when (TaskType.valueOf(task.getString("type"))) {
                            TaskType.T1 -> CampaignTask.T1(id, task.getInt("n"))
                            TaskType.T2 -> CampaignTask.T2(id, task.getString("letter").single(), task.getInt("n"))
                            TaskType.T3 -> CampaignTask.T3(id, task.getInt("minLength"), task.getString("hintTarget"))
                            TaskType.T4 -> CampaignTask.T4(id, task.getJSONArray("targets").strings())
                            TaskType.T5 -> CampaignTask.T5(id, task.getString("ending"), task.getInt("minLength"))
                            TaskType.T6 -> CampaignTask.T6(id, task.getString("category"), task.getInt("n"), task.getJSONArray("words").strings())
                            TaskType.T7 -> CampaignTask.T7(id, task.getString("letter").single(), task.getInt("n"))
                        }
                    } catch (error: RuntimeException) {
                        throw IllegalArgumentException("level=$number task=$id: ${error.message}", error)
                    }
                })
        },
    )

    private fun <T> combinations(items: List<T>, count: Int): List<List<T>> {
        if (count == 0) return listOf(emptyList())
        if (count < 0 || items.size < count) return emptyList()
        return combinations(items.drop(1), count - 1).map { listOf(items.first()) + it } +
            combinations(items.drop(1), count)
    }

    fun audit(input: JSONObject): JSONObject {
        val errors = JSONArray()
        val levels = JSONArray()
        val outputHints = JSONObject()
        val output = JSONObject().put("errors", errors).put("levels", levels).put("hints", outputHints)
            .put("alphabet", Alphabet.LETTERS).put("runtime", System.getProperty("java.runtime.version"))
        fun error(reason: String, level: CampaignLevel? = null, task: CampaignTask? = null) {
            errors.put(JSONObject().put("level", level?.number ?: JSONObject.NULL)
                .put("task", task?.id ?: JSONObject.NULL).put("reason", reason))
        }
        val campaign = try { decode(input.getJSONObject("campaign")) } catch (e: RuntimeException) {
            error(e.message ?: "Invalid campaign JSON")
            return output
        }
        val structural = if (input.optBoolean("production", true)) ProductionCampaignValidator.validate(campaign)
            else CampaignValidator.validate(campaign)
        for (problem in structural) {
            errors.put(JSONObject().put("level", problem.levelNumber ?: JSONObject.NULL)
                .put("task", problem.taskId ?: JSONObject.NULL).put("reason", "${problem.field}: ${problem.reason}"))
        }
        val index = WordIndex(input.getJSONArray("dictionary").strings())
        val hints = input.getJSONObject("hintInputs")
        val wordData = hints.keySet().associateWith { word ->
            val row = hints.getJSONObject(word)
            val forms = row.getJSONArray("forms").strings().toSet()
            val related = row.getJSONArray("related").strings().toSet()
            val definition = row.getJSONArray("definitions").strings().firstOrNull {
                DefinitionHint.prepare(word, it, forms, related) != null
            }
            val prepared = definition?.let { DefinitionHint.prepare(word, it, forms, related) }
            outputHints.put(word, prepared ?: JSONObject.NULL)
            HintWordData(definition = definition, forms = forms, related = related, familiar = true)
        }
        var checked = 0
        var chain = CampaignProgress()
        var fullChain = CampaignProgress()
        for (level in campaign.levels) {
            if (level.word in forbidden) error("Prohibited source", level)
            val game = GameState.start(level.word, index)
            val initial = CurrentCampaignLevel(level, game)
            if (level.word in game.allWords) error("Source appears among answers", level)
            if (game.submit(level.word).result != AnswerResult.SAME_AS_SOURCE) error("Source accepted", level)
            val all = game.allWords.fold(initial) { state, word ->
                val submission = TaskRules.submit(state, word, campaign.countMinLength, index)
                if (submission.result != AnswerResult.CORRECT) error("Legal answer rejected: $word", level)
                submission.evaluation.state
            }
            if (all.completedTaskIds.size != level.maxStars) error("All legal answers do not give M", level)
            val taskRows = JSONArray()
            val witnesses = mutableMapOf<String, List<String>>()
            for (task in level.tasks) {
                val single = initial.copy(level = level.copy(tasks = listOf(task), requiredStars = 1))
                // A positive progress change for a singleton answer defines its accepted set.
                val singletonProgress = game.allWords.associateWith { word ->
                    TaskRules.submit(single, word, campaign.countMinLength, index).evaluation.progress.single().current
                }.filterValues { it > 0 }.toSortedMap()
                val accepted = singletonProgress.keys.toList()
                val target = TaskRules.evaluate(single, campaign.countMinLength).progress.single().target
                val witness = accepted.take(target)
                witnesses[task.id] = witness
                val alone = witness.fold(single) { state, word ->
                    TaskRules.submit(state, word, campaign.countMinLength, index).evaluation.state
                }
                if (task.id !in alone.completedTaskIds) error("Task individually unreachable", level, task)
                val assigned = when (task) {
                    is CampaignTask.T3 -> listOf(task.hintTarget)
                    is CampaignTask.T4 -> task.targets
                    else -> emptyList()
                }
                for (word in assigned) {
                    if (word in forbidden || word !in game.allWords) error("Illegal assigned target: $word", level, task)
                    if (task is CampaignTask.T4) {
                        val data = wordData[word]
                        val text = outputHints.opt(word)
                        if (data == null || text == null || text == JSONObject.NULL) {
                            error("T4 has no useful masked hint: $word", level, task)
                        } else {
                            val oneRiddle = CampaignTask.T4(task.id, listOf(word))
                            var state = single.copy(level = single.level.copy(tasks = listOf(oneRiddle)))
                            val ladder = HintLadder.build(HintTarget(word, task.id, task.type), oneRiddle, data, Random(0))
                            if (ladder.steps.firstOrNull { it.stage == HintStage.DEFINITION }?.text != text) {
                                error("T4 ladder differs from prepared hint: $word", level, task)
                            }
                            // Definition/length are free for a riddle; use public runtime API as well.
                            repeat(2) {
                                val hint = HintRules.next(state, mapOf(word to data), campaign.countMinLength, Random(0))
                                if (hint.status != HintStatus.SHOWN || hint.state.hints.paidUnits != 0) {
                                    error("T4 free hint unavailable: $word", level, task)
                                }
                                state = hint.state
                            }
                        }
                    }
                }
                taskRows.put(JSONObject().put("id", task.id).put("accepted", accepted)
                    .put("target", target).put("singleton_progress", singletonProgress))
            }
            levels.put(JSONObject().put("number", level.number).put("answers", game.allWords.sorted()).put("tasks", taskRows))
            if (structural.isNotEmpty()) continue
            // Each branch starts at the same reachable frontier. Only local words/IDs affect it;
            // thus all branches can be composed across 30 levels without a Cartesian explosion.
            for (chosen in combinations(level.tasks, level.requiredStars)) {
                checked++
                val words = chosen.flatMap { witnesses.getValue(it.id) }.distinct()
                val current = words.fold(initial) { state, word ->
                    TaskRules.submit(state, word, campaign.countMinLength, index).evaluation.state
                }
                val recorded = CampaignRules.recordLevel(campaign, chain, current)
                if (current.completedTaskIds.size < level.requiredStars ||
                    (level.number == campaign.levels.size && !recorded.finaleReached) ||
                    (level.number < campaign.levels.size && !CampaignRules.isUnlocked(campaign, recorded, level.number + 1))) {
                    error("R branch fails to reach next level/finale: ${chosen.map { it.id }}", level)
                }
            }
            val firstR = level.tasks.take(level.requiredStars).flatMap { witnesses.getValue(it.id) }.distinct()
            val current = firstR.fold(initial) { state, word ->
                TaskRules.submit(state, word, campaign.countMinLength, index).evaluation.state
            }
            chain = CampaignRules.recordLevel(campaign, chain, current)
            fullChain = CampaignRules.recordLevel(campaign, fullChain, all)
            if (!CampaignRules.isPassed(level, chain)) break
        }
        if (structural.isEmpty() && (!chain.finaleReached || !CampaignRules.summary(campaign, fullChain).isMaster)) {
            error("R chain/finale or all-answers master failed")
        }
        return output.put("r_combinations_checked", checked).put("finale_reached", chain.finaleReached)
            .put("all_answer_stars", CampaignRules.summary(campaign, fullChain).stars)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected input bundle and output JSON paths" }
        val result = audit(JSONObject(File(args[0]).readText()))
        File(args[1]).writeText(result.toString(2) + "\n")
        if (result.getJSONArray("errors").length() != 0) exitProcess(2)
    }
}
