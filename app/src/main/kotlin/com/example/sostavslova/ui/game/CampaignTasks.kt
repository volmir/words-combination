package com.example.sostavslova.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    Column(Modifier.fillMaxWidth().testTag("game_tasks"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.game_tasks_summary,
            current.level.tasks.count { it.id in current.completedTaskIds }, current.level.tasks.size),
            Modifier.testTag("game_tasks_summary").semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        for (task in current.level.tasks) {
            val progress = state.taskProgress.firstOrNull { it.id == task.id }
            val done = task.id in current.completedTaskIds
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .testTag("level_task_${task.type.name.lowercase()}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskRow(taskTitle(task), done)
                Text(stringResource(R.string.game_task_progress, progress?.current ?: 0, progress?.target ?: 1),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (done) R.string.game_task_done else R.string.game_task_pending),
                    style = MaterialTheme.typography.bodyMedium)
                if (task is CampaignTask.T4) {
                    state.riddles[task.id].orEmpty().forEachIndexed { index, riddle ->
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp)
                            .testTag("level_riddle_${index + 1}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.game_riddle_number, index + 1), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(if (riddle.solved) R.string.game_riddle_solved else R.string.game_riddle_pending),
                                style = MaterialTheme.typography.bodyMedium)
                            RiddleText(riddle)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RiddleText(riddle: RiddleUiState) {
    if (riddle.text != null) {
        Text(riddle.text, style = MaterialTheme.typography.bodyLarge)
        if (riddle.wiktionary) Text(stringResource(R.string.hint_definition_source), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(R.string.hint_fallback, riddle.length, letterForm(riddle.length), riddle.firstLetter),
            style = MaterialTheme.typography.bodyLarge)
        if (com.example.sostavslova.BuildConfig.DEBUG) Text(stringResource(R.string.game_riddle_fallback), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun categoryName(key: String): String = stringResource(
    com.example.sostavslova.data.CategoryNames.resource(key) ?: R.string.game_category_unknown)
