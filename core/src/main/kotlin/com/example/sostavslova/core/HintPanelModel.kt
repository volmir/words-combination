package com.example.sostavslova.core

/** Ключи локализованных подписей; ни Compose, ни Android-ресурсов в модели нет. */
enum class HintPanelLabel { AUTO, MIN_LENGTH, START_LETTER, RIDDLES, RIDDLE_NUMBER, ENDING, CATEGORY, CONTAINS_LETTER }

/** Без полного ответа: только идентификатор задания и параметры подписи. */
data class HintPanelTask(
    val taskId: String? = null,
    val label: HintPanelLabel = HintPanelLabel.AUTO,
    val number: Int = 0,
    val argument: String = "",
)

/** Готовый снимок панели. Строится при обновлении уровня, а не при вводе букв. */
data class HintPanelModel(
    val target: HintPanelTask,
    val options: List<HintPanelTask>,
    val selectedTaskId: String?,
    val selectionLocked: Boolean,
    val pattern: String?,
    val openedLetters: Int,
    val wordLength: Int,
    val ladderComplete: Boolean,
    val cost: Int?,
    val remaining: Int,
) {
    companion object {
        /** Только чтение: цены, выбор цели и фиксация остаются в [HintRules]. */
        fun of(current: CurrentCampaignLevel, cost: Int?, remaining: Int, countMinLength: Int): HintPanelModel {
            val restored = HintRules.restore(current)
            val step = HintRules.currentStep(restored)
            val found = restored.game.found.map(Alphabet::normalize).toSet()
            val ladder = restored.hints.ladder.takeUnless { it.target?.word in found } ?: HintLadder()
            val target = ladder.target
            val available = HintRules.taskOptions(restored, countMinLength)
            val preferred = restored.hints.preferredTaskId?.takeIf { id -> available.any { it.id == id } }
            val task = restored.level.tasks.firstOrNull { it.id == target?.taskId }
                ?: available.firstOrNull { it.id == preferred }
            val pattern = step?.text?.takeIf { it.isNotEmpty() }
            val options = if (restored.level.tasks.size > 1 && available.isNotEmpty()) {
                listOf(HintPanelTask()) + available.map { option ->
                    describe(option, countMinLength, target?.takeIf { it.taskId == option.id }?.word)
                }
            } else emptyList()
            return HintPanelModel(
                target = task?.let { describe(it, countMinLength, target?.word) } ?: HintPanelTask(),
                options = options,
                selectedTaskId = preferred,
                selectionLocked = ladder.paidSteps > 0,
                pattern = pattern,
                openedLetters = pattern?.count { it != '_' } ?: 0,
                wordLength = target?.word?.length ?: pattern?.length ?: 0,
                ladderComplete = pattern != null && ladder.steps.isNotEmpty() && ladder.shown.size == ladder.steps.size,
                cost = cost,
                remaining = remaining,
            )
        }

        private fun describe(task: CampaignTask, countMinLength: Int, targetWord: String?): HintPanelTask = when (task) {
            is CampaignTask.T1 -> HintPanelTask(task.id, HintPanelLabel.MIN_LENGTH, number = countMinLength)
            is CampaignTask.T2 -> HintPanelTask(task.id, HintPanelLabel.START_LETTER, argument = task.letter.uppercaseChar().toString())
            is CampaignTask.T3 -> HintPanelTask(task.id, HintPanelLabel.MIN_LENGTH, number = task.minLength)
            is CampaignTask.T4 -> {
                val index = task.targets.indexOf(targetWord)
                if (index >= 0) HintPanelTask(task.id, HintPanelLabel.RIDDLE_NUMBER, number = index + 1)
                else HintPanelTask(task.id, HintPanelLabel.RIDDLES)
            }
            is CampaignTask.T5 -> HintPanelTask(task.id, HintPanelLabel.ENDING, argument = task.ending)
            is CampaignTask.T6 -> HintPanelTask(task.id, HintPanelLabel.CATEGORY, argument = task.category)
            is CampaignTask.T7 -> HintPanelTask(task.id, HintPanelLabel.CONTAINS_LETTER, argument = task.letter.uppercaseChar().toString())
        }
    }
}
