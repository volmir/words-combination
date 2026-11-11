package com.example.sostavslova.data

import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.CampaignValidator
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.TaskType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/** Wire decoding only. Domain structure and task parameter validation belong to :core. */
internal object CampaignJson {
    fun decode(text: String): Campaign {
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Expected campaign object")
        val campaign = Campaign(
            version = root.integer("version"),
            countMinLength = root.integer("countMinLength"),
            stages = root.objects("stages").map {
                Stage(it.integer("number"), it.integer("firstLevel"), it.integer("lastLevel"))
            },
            levels = root.objects("levels").map { level ->
                CampaignLevel(
                    number = level.integer("number"),
                    word = level.string("word"),
                    stage = level.integer("stage"),
                    requiredStars = level.integer("requiredStars"),
                    tasks = level.objects("tasks").map(::task),
                )
            },
        )
        val errors = CampaignValidator.validate(campaign)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return campaign
    }

    private fun task(task: JsonObject): CampaignTask {
        val id = task.string("id")
        return when (TaskType.valueOf(task.string("type"))) {
            TaskType.T1 -> CampaignTask.T1(id, task.integer("n"))
            TaskType.T2 -> CampaignTask.T2(id, task.string("letter").single(), task.integer("n"))
            TaskType.T3 -> CampaignTask.T3(id, task.integer("minLength"), task.string("hintTarget"))
            TaskType.T4 -> CampaignTask.T4(id, task.strings("targets"))
            TaskType.T5 -> CampaignTask.T5(id, task.string("ending"), task.integer("minLength"))
            TaskType.T6 -> CampaignTask.T6(id, task.string("category"), task.integer("n"), task.strings("words"))
            TaskType.T7 -> CampaignTask.T7(id, task.string("letter").single(), task.integer("n"))
        }
    }

    private fun JsonObject.integer(key: String): Int {
        val value = get(key) as? JsonPrimitive
        require(value != null && !value.isString) { "Expected integer: $key" }
        return value.int
    }

    private fun JsonObject.string(key: String): String = stringValue(get(key), key)

    private fun stringValue(value: Any?, key: String): String {
        require(value is JsonPrimitive && value.isString) { "Expected string: $key" }
        return value.content
    }

    private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray
        ?: throw IllegalArgumentException("Expected array: $key")

    private fun JsonObject.objects(key: String): List<JsonObject> = array(key).map {
        it as? JsonObject ?: throw IllegalArgumentException("Expected object in $key")
    }

    private fun JsonObject.strings(key: String): List<String> = array(key).map { stringValue(it, key) }
}
