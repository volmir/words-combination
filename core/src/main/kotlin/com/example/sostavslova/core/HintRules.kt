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

    fun budget(current: CurrentCampaignLevel): HintBudget = HintBudget(
        initial = HintEconomy.initialBudget(current.level.stage),
        earned = normalizedFound(current).size / HintEconomy.earnEvery,
        spent = current.hints.paidUnits,
    )

    /** Чистое восстановление старого формата без изменения бюджета, ответов и звёзд. */
    fun restore(current: CurrentCampaignLevel, words: Map<String, HintWordData> = emptyMap()): CurrentCampaignLevel {
        val ladder = current.hints.ladder
        val task = current.level.tasks.firstOrNull { it.id == ladder.target?.taskId }
        return current.copy(hints = current.hints.copy(ladder = ladder.lettersOnly(task, words[ladder.target?.word]?.knownPositions.orEmpty())))
    }

    /** Чтение при повороте/возврате: не выбирает цель и ничего не списывает. */
    fun currentStep(current: CurrentCampaignLevel): HintStep? =
        restore(current).hints.ladder.takeUnless { it.target?.word in normalizedFound(current) }?.shown?.lastOrNull()

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
        var hints = restore(current, words).hints
        // Предпочтение игрока проверяется по текущей кампании; недопустимое значение
        // сбрасывается, чтобы следующий выбор был автоматическим.
        val candidates = current.game.allWords.filter { it !in found && it !in forbiddenTargets }.distinct()
        val completed = TaskRules.evaluate(current, countMinLength).state.completedTaskIds
        val preferred = hints.preferredTaskId
        if (preferred != null) {
            val task = current.level.tasks.firstOrNull { it.id == preferred }
            val eligible = if (task == null || task.id in completed) emptyList()
            else eligibleWords(task, candidates, countMinLength)
            if (task == null || task.id in completed || eligible.isEmpty()) {
                hints = hints.copy(preferredTaskId = null)
            }
        }
        if (hints.ladder.target == null || hints.ladder.target.word in found) {
            val target = selectTarget(current.copy(hints = hints), words, countMinLength, random)
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
        val free = HintEconomy.t4FirstStepFree &&
            target.taskType == TaskType.T4 &&
            hints.ladder.nextStepIndex == 0 &&
            step.stage != HintStage.ANSWER
        val cost = if (free) 0 else HintEconomy.stepCost
        if (budget(state).remaining < cost) return HintResult(state, null, HintStatus.BUDGET_EXHAUSTED)
        val next = hints.copy(
            ladder = hints.ladder.copy(
                nextStepIndex = hints.ladder.nextStepIndex + 1,
                revealedPositions = hints.ladder.revealedPositions + step.revealedPositions,
                paidSteps = hints.ladder.paidSteps + cost,
            ),
            shownSteps = hints.shownSteps + 1,
            paidUnits = hints.paidUnits + cost,
        )
        return HintResult(current.copy(hints = next), step, HintStatus.SHOWN)
    }

    /** Предварительная цена без записи, расхода и изменения сохранённой лестницы. */
    fun nextCost(current: CurrentCampaignLevel, countMinLength: Int = 4): Int? {
        val preview = next(current, countMinLength = countMinLength, random = Random(0))
        return when (preview.status) {
            HintStatus.SHOWN -> preview.state.hints.paidUnits - current.hints.paidUnits
            HintStatus.BUDGET_EXHAUSTED -> HintEconomy.stepCost
            HintStatus.NO_TARGET, HintStatus.LADDER_COMPLETE -> null
        }
    }

    /**
     * Незавершённые задания, для которых ещё есть ненайденные подходящие слова.
     * Только чтение: порядок уровня сохраняется, цель и счётчики не меняются.
     */
    fun taskOptions(current: CurrentCampaignLevel, countMinLength: Int = 4): List<CampaignTask> {
        val found = normalizedFound(current)
        val candidates = current.game.allWords.filter { it !in found && it !in forbiddenTargets }.distinct()
        val completed = TaskRules.evaluate(current, countMinLength).state.completedTaskIds
        return current.level.tasks.filter { task ->
            task.id !in completed && eligibleWords(task, candidates, countMinLength).isNotEmpty()
        }
    }

    /** Выбор задания для подсказки. Возвращает состояние с обновлённым предпочтением. */
    fun selectTask(
        current: CurrentCampaignLevel,
        taskId: String? = null,
        countMinLength: Int = 4,
    ): CurrentCampaignLevel {
        val hints = current.hints
        // После покупки платной ступени цель фиксируется, пока её слово не найдено.
        val targetFound = hints.ladder.target?.word?.let { it in normalizedFound(current) } == true
        if (hints.ladder.paidSteps > 0 && hints.ladder.target != null && !targetFound) return current
        val valid = taskId == null || taskOptions(current, countMinLength).any { it.id == taskId }
        val newPreferred = if (valid) taskId else null
        val changed = newPreferred != hints.preferredTaskId
        val newLadder = if (changed) HintLadder() else hints.ladder
        return current.copy(hints = hints.copy(preferredTaskId = newPreferred, ladder = newLadder))
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
        // Предпочтение игрока имеет приоритет над автоматическим порядком.
        val preferredId = current.hints.preferredTaskId
        if (preferredId != null) {
            val task = unfinished.firstOrNull { it.id == preferredId }
            if (task != null) {
                val eligible = eligibleWords(task, candidates, countMinLength)
                val word = selectWord(task, eligible, words, random, countMinLength)
                if (word != null) {
                    val letter = when (task) {
                        is CampaignTask.T2 -> task.letter
                        is CampaignTask.T7 -> task.letter
                        else -> null
                    }
                    return HintTarget(word, task.id, task.type, letter)
                }
            }
        }
        val t4s = unfinished.filterIsInstance<CampaignTask.T4>()
        val others = unfinished.filterNot { it is CampaignTask.T4 }
        val sortedOthers = others.sortedWith(
            compareBy<CampaignTask>(
                { eligibleWords(it, candidates, countMinLength).size },
                { current.level.tasks.indexOf(it) },
            )
        )
        val ordered = t4s + sortedOthers
        for (task in ordered) {
            val eligible = eligibleWords(task, candidates, countMinLength)
            val word = selectWord(task, eligible, words, random, countMinLength) ?: continue
            val letter = when (task) {
                is CampaignTask.T2 -> task.letter
                is CampaignTask.T7 -> task.letter
                else -> null
            }
            return HintTarget(word, task.id, task.type, letter)
        }
        return choose(candidates, words, random)?.let { HintTarget(it) }
    }

    private fun eligibleWords(task: CampaignTask, candidates: List<String>, countMinLength: Int): List<String> =
        candidates.filter { word ->
            when (task) {
                is CampaignTask.T1 -> word.length >= countMinLength
                is CampaignTask.T2 -> word.length >= countMinLength && word.first() == task.letter
                is CampaignTask.T3 -> word.length >= task.minLength
                is CampaignTask.T4 -> word in task.targets
                is CampaignTask.T5 -> word.length >= task.minLength && word.endsWith(task.ending)
                is CampaignTask.T6 -> word in task.words
                is CampaignTask.T7 -> task.letter in word
            }
        }

    private fun selectWord(
        task: CampaignTask,
        eligible: List<String>,
        words: Map<String, HintWordData>,
        random: Random,
        countMinLength: Int,
    ): String? {
        if (eligible.isEmpty()) return null
        return if (task is CampaignTask.T3) {
            val minLen = eligible.minOf { it.length }
            val shortest = eligible.filter { it.length == minLen }
            if (task.hintTarget in shortest) task.hintTarget else shortest.random(random)
        } else {
            choose(eligible, words, random)
        }
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
