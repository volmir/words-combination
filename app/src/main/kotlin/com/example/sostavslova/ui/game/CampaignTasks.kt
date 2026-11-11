package com.example.sostavslova.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.core.TaskType

@Composable
internal fun CampaignTasks(state: GameUiState) {
    val current = state.campaignLevel ?: return
    for (task in current.level.tasks) {
        val progress = state.taskProgress.firstOrNull { it.id == task.id }
        Column(Modifier.testTag("level_task_${task.type.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskRow(taskTitle(task), task.id in current.completedTaskIds)
            Text(stringResource(R.string.game_task_progress, progress?.current ?: 0, progress?.target ?: 1))
            if (task is CampaignTask.T4) {
                state.riddles[task.id].orEmpty().forEachIndexed { index, riddle ->
                    Column(Modifier.testTag("level_riddle_${index + 1}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.game_riddle_number, index + 1), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (riddle.solved) R.string.game_riddle_solved else R.string.game_riddle_pending))
                        if (riddle.text != null) {
                            Text(riddle.text)
                            if (riddle.wiktionary) Text(stringResource(R.string.hint_definition_source), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource(R.string.hint_fallback, riddle.length, letterForm(riddle.length), riddle.firstLetter))
                            Text(stringResource(R.string.game_riddle_fallback), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun taskTitle(task: CampaignTask): String = when (task) {
    is CampaignTask.T1 -> stringResource(R.string.game_task_t1, task.n, RussianPlural.words(task.n))
    is CampaignTask.T2 -> stringResource(R.string.game_task_t2, task.n, RussianPlural.words(task.n), task.letter.toString())
    is CampaignTask.T3 -> stringResource(R.string.game_task_t3, task.minLength, letterForm(task.minLength))
    is CampaignTask.T4 -> stringResource(R.string.game_task_t4)
    is CampaignTask.T5 -> stringResource(R.string.game_task_t5, task.ending, task.minLength, letterForm(task.minLength))
    is CampaignTask.T6 -> stringResource(R.string.game_task_t6, task.n, RussianPlural.words(task.n), categoryName(task.category))
    is CampaignTask.T7 -> stringResource(R.string.game_task_t7, task.n, RussianPlural.words(task.n), task.letter.toString())
}

@Composable
private fun letterForm(n: Int) = RussianPlural.form(n, stringResource(R.string.hint_letter_genitive_one),
    stringResource(R.string.hint_letter_genitive_many), stringResource(R.string.hint_letter_genitive_many))

internal fun explanationText(type: TaskType): Int = when (type) {
    TaskType.T1 -> R.string.game_explain_t1
    TaskType.T2 -> R.string.game_explain_t2
    TaskType.T3 -> R.string.game_explain_t3
    TaskType.T4 -> R.string.game_explain_t4
    TaskType.T5 -> R.string.game_explain_t5
    TaskType.T6 -> R.string.game_explain_t6
    TaskType.T7 -> R.string.game_explain_t7
}

@Composable
internal fun gameStageName(number: Int): String = stringResource(when (number) {
    1 -> R.string.stage_warmup
    2 -> R.string.stage_initial
    3 -> R.string.stage_long
    4 -> R.string.stage_riddles
    5 -> R.string.stage_endings
    else -> R.string.stage_master
})

// Ключ берётся из campaign data; русский текст доступен независимо от базы определений.
@Composable
private fun categoryName(key: String): String = stringResource(when (key) {
    "animals" -> R.string.game_category_animals
    "birds" -> R.string.game_category_birds
    "fish" -> R.string.game_category_fish
    "insects" -> R.string.game_category_insects
    "plants" -> R.string.game_category_plants
    "fruits" -> R.string.game_category_fruits
    "vegetables" -> R.string.game_category_vegetables
    "food" -> R.string.game_category_food
    "drinks" -> R.string.game_category_drinks
    "cooking" -> R.string.game_category_cooking
    "home" -> R.string.game_category_home
    "furniture" -> R.string.game_category_furniture
    "utensils" -> R.string.game_category_utensils
    "clothing" -> R.string.game_category_clothing
    "tools" -> R.string.game_category_tools
    "technology" -> R.string.game_category_technology
    "transport" -> R.string.game_category_transport
    "buildings" -> R.string.game_category_buildings
    "places" -> R.string.game_category_places
    "nature" -> R.string.game_category_nature
    "weather" -> R.string.game_category_weather
    "materials" -> R.string.game_category_materials
    "body" -> R.string.game_category_body
    "health" -> R.string.game_category_health
    "people" -> R.string.game_category_people
    "family" -> R.string.game_category_family
    "professions" -> R.string.game_category_professions
    "society" -> R.string.game_category_society
    "education" -> R.string.game_category_education
    "science" -> R.string.game_category_science
    "language" -> R.string.game_category_language
    "literature" -> R.string.game_category_literature
    "art" -> R.string.game_category_art
    "music" -> R.string.game_category_music
    "sport" -> R.string.game_category_sport
    "work" -> R.string.game_category_work
    "time" -> R.string.game_category_time
    "feelings" -> R.string.game_category_feelings
    "actions" -> R.string.game_category_actions
    "other" -> R.string.game_category_other
    else -> R.string.game_category_unknown
})
