package com.example.sostavslova.core

/** Числитель ограничен [target]; [completed] учитывает и ранее полученную звезду. */
data class TaskProgress(val id: String, val current: Int, val target: Int, val completed: Boolean)

/** [state] нужно сохранить и передать следующему вычислению для однократной выдачи звёзд. */
data class TaskEvaluation(
    val state: CurrentCampaignLevel,
    val progress: List<TaskProgress>,
    val newlyCompletedTaskIds: Set<String>,
) {
    val stars: Int get() = state.completedTaskIds.size
}

data class TaskSubmission(val result: AnswerResult, val evaluation: TaskEvaluation)

/**
 * Правила заданий для проверенных данных кампании. Игра и сохранённые ID относятся
 * только к текущему уровню; found содержит принятые GameState ответы, а не сырой ввод.
 * Параметры заданий канонические (CampaignValidator), countMinLength — из Campaign.
 */
object TaskRules {
    /** Проверка ответа остаётся в GameState; задания используют его итоговое состояние. */
    fun submit(current: CurrentCampaignLevel, raw: String, countMinLength: Int): TaskSubmission {
        val submission = current.game.submit(raw)
        return TaskSubmission(
            submission.result,
            evaluate(current.copy(game = submission.state), countMinLength),
        )
    }

    /**
     * Пересчёт по уникальным нормализованным ответам данного уровня, в том числе после
     * восстановления. Ранее выполненные задания сохраняются независимо от found.
     * Звёзды, восстановленные по found при отсутствии сохранённого ID, выдаются один раз.
     */
    fun evaluate(current: CurrentCampaignLevel, countMinLength: Int): TaskEvaluation {
        val found = current.game.found.map(Alphabet::normalize).toSet()
        val progress = current.level.tasks.map { task ->
            val (count, target) = when (task) {
                is CampaignTask.T1 -> found.count { it.length >= countMinLength } to task.n
                is CampaignTask.T2 -> found.count {
                    it.length >= countMinLength && it.firstOrNull() == task.letter
                } to task.n
                is CampaignTask.T3 -> found.count { it.length >= task.minLength } to 1
                is CampaignTask.T4 -> task.targets.count { it in found } to task.targets.size
                is CampaignTask.T5 -> found.count {
                    it.length >= task.minLength && it.endsWith(task.ending)
                } to 1
                is CampaignTask.T6 -> {
                    val thematicWords = task.words.toHashSet()
                    found.count { it in thematicWords } to task.n
                }
                is CampaignTask.T7 -> found.count { task.letter in it } to task.n
            }
            TaskProgress(
                id = task.id,
                current = count.coerceAtMost(target),
                target = target,
                completed = count >= target || task.id in current.completedTaskIds,
            )
        }
        val completed = current.completedTaskIds + progress.filter { it.completed }.map { it.id }
        return TaskEvaluation(
            state = current.copy(completedTaskIds = completed),
            progress = progress,
            newlyCompletedTaskIds = completed - current.completedTaskIds,
        )
    }
}
