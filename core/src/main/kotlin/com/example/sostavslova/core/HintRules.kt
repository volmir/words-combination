package com.example.sostavslova.core

import kotlin.random.Random

data class HintBudget(val initial: Int, val earned: Int, val spent: Int) {
    val remaining: Int get() = (initial + earned - spent).coerceAtLeast(0)
}

enum class HintStatus { SHOWN, BUDGET_EXHAUSTED, NO_TARGET, LADDER_COMPLETE }

/** [state] сохраняется атомарно перед показом [step]; null означает, что нового показа нет. */
data class HintResult(val state: CurrentCampaignLevel, val step: HintStep?, val status: HintStatus)

/** Чистые правила v2 для валидированной кампании и составимых ответов из GameState.start. */
object HintRules {
    private val forbiddenTargets = setOf("прелюбодейка", "брудершафт", "праймериз")

    fun budget(current: CurrentCampaignLevel): HintBudget {
        val initial = when (current.level.stage) {
            1, 2 -> 5
            3, 4 -> 4
            5, 6 -> 3
            else -> error("Unknown campaign stage: ${current.level.stage}")
        }
        return HintBudget(initial, normalizedFound(current).size / 4, current.hints.paidUnits)
    }

    /** Чтение при повороте/возврате: не выбирает цель и ничего не списывает. */
    fun currentStep(current: CurrentCampaignLevel): HintStep? =
        current.hints.ladder.takeUnless { it.target?.word in normalizedFound(current) }?.shown?.lastOrNull()

    /**
     * Ровно один явный запрос нового шага. Повторный вызов с возвращённым state —
     * следующий запрос, а не перерисовка. Random используется только для новой цели
     * и её лестницы. Не перебирает общий словарь и не обращается к БД.
     */
    fun next(
        current: CurrentCampaignLevel,
        words: Map<String, HintWordData> = emptyMap(),
        countMinLength: Int = 4,
        random: Random = Random.Default,
    ): HintResult {
        val found = normalizedFound(current)
        var hints = current.hints
        if (hints.ladder.target == null || hints.ladder.target.word in found) {
            val target = selectTarget(current, words, countMinLength, random)
            val ladder = if (target == null) HintLadder() else HintLadder.build(
                target,
                current.level.tasks.firstOrNull { it.id == target.taskId },
                words[target.word] ?: HintWordData(),
                random,
            )
            hints = hints.copy(ladder = ladder)
        }
        val state = current.copy(hints = hints)
        val target = hints.ladder.target ?: return HintResult(state, null, HintStatus.NO_TARGET)
        val step = hints.ladder.steps.getOrNull(hints.ladder.nextStepIndex)
            ?: return HintResult(state, null, HintStatus.LADDER_COMPLETE)
        val free = target.taskType == TaskType.T4 && step.stage in setOf(
            HintStage.THEME, HintStage.DEFINITION, HintStage.LENGTH,
        )
        if (!free && budget(state).remaining == 0) return HintResult(state, null, HintStatus.BUDGET_EXHAUSTED)
        val next = hints.copy(
            ladder = hints.ladder.copy(
                nextStepIndex = hints.ladder.nextStepIndex + 1,
                revealedPositions = hints.ladder.revealedPositions + step.revealedPositions,
            ),
            shownSteps = hints.shownSteps + 1,
            paidUnits = hints.paidUnits + if (free) 0 else 1,
        )
        return HintResult(current.copy(hints = next), step, HintStatus.SHOWN)
    }

    /** Предварительная цена без записи, расхода и изменения сохранённой лестницы. */
    fun nextCost(current: CurrentCampaignLevel, countMinLength: Int = 4): Int? {
        val preview = next(current, countMinLength = countMinLength, random = Random(0))
        return when (preview.status) {
            HintStatus.SHOWN -> preview.state.hints.paidUnits - current.hints.paidUnits
            HintStatus.BUDGET_EXHAUSTED -> 1
            HintStatus.NO_TARGET, HintStatus.LADDER_COMPLETE -> null
        }
    }

    private fun selectTarget(
        current: CurrentCampaignLevel,
        words: Map<String, HintWordData>,
        countMinLength: Int,
        random: Random,
    ): HintTarget? {
        val found = normalizedFound(current)
        // allWords уже проверены WordIndex при открытии уровня; здесь только местные ответы.
        val candidates = current.game.allWords.filter { it !in found && it !in forbiddenTargets }.distinct().sorted()
        if (candidates.isEmpty()) return null
        val completed = TaskRules.evaluate(current, countMinLength).state.completedTaskIds
        val unfinished = current.level.tasks.filter { it.id !in completed }
        val ordered = unfinished.filterIsInstance<CampaignTask.T4>() + unfinished.filterNot { it is CampaignTask.T4 }
        for (task in ordered) {
            val eligible = candidates.filter { word ->
                when (task) {
                    is CampaignTask.T1 -> word.length >= countMinLength
                    is CampaignTask.T2 -> word.length >= countMinLength && word.first() == task.letter
                    is CampaignTask.T3 -> word == task.hintTarget && word.length >= task.minLength
                    is CampaignTask.T4 -> word in task.targets
                    is CampaignTask.T5 -> word.length >= task.minLength && word.endsWith(task.ending)
                    is CampaignTask.T6 -> word in task.words
                    is CampaignTask.T7 -> task.letter in word
                }
            }
            val word = choose(eligible, words, random) ?: continue
            val letter = when (task) {
                is CampaignTask.T2 -> task.letter
                is CampaignTask.T7 -> task.letter
                else -> null
            }
            return HintTarget(word, task.id, task.type, letter)
        }
        return choose(candidates, words, random)?.let { HintTarget(it) }
    }

    private fun choose(candidates: List<String>, words: Map<String, HintWordData>, random: Random): String? {
        if (candidates.isEmpty()) return null
        fun quality(word: String): Int {
            val data = words[word] ?: HintWordData()
            return (if (data.undesirableLabels.isEmpty()) 2 else 0) + (if (data.familiar) 1 else 0)
        }
        val best = candidates.maxOf(::quality)
        return candidates.filter { quality(it) == best }.random(random)
    }

    private fun normalizedFound(current: CurrentCampaignLevel): Set<String> =
        current.game.found.map(Alphabet::normalize).toSet()
}
