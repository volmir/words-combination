package com.example.sostavslova.core

import kotlin.random.Random

enum class HintStage { THEME, DEFINITION, LENGTH, FIRST_LETTER, PATTERN, ANSWER }

/** Ключ темы отделён от проверяемого текста: ключ не показывается игроку. */
data class HintCategory(val key: String, val text: String)

/** Необязательные локальные метаданные; ключи карты слов уже нормализованы. */
data class HintWordData(
    val category: HintCategory? = null,
    val definition: String? = null,
    val forms: Set<String> = emptySet(),
    val related: Set<String> = emptySet(),
    val familiar: Boolean = false,
    val undesirableLabels: Set<String> = emptySet(),
    val knownPositions: Set<Int> = emptySet(),
)

/** Контекст назначения сохраняется до угадывания, даже если задание уже закрыто другим ответом. */
data class HintTarget(
    val word: String = "",
    val taskId: String? = null,
    val taskType: TaskType? = null,
    val letter: Char? = null,
)

/** Старые виды шагов и поля оставлены для чтения сохранений. Новые шаги содержат шаблон. */
data class HintStep(
    val stage: HintStage = HintStage.LENGTH,
    val text: String? = null,
    val length: Int? = null,
    val revealedPositions: Set<Int> = emptySet(),
    val wiktionary: Boolean = false,
)

/**
 * Снимок лестницы: подготовленные тексты и порядок позиций переживают отсутствие БД
 * и смену Random. UI показывает только [shown], а не будущие шаги из [steps].
 */
data class HintLadder(
    val target: HintTarget? = null,
    val steps: List<HintStep> = emptyList(),
    val nextStepIndex: Int = 0,
    val revealedPositions: Set<Int> = emptySet(),
    val letterLadderVersion: Int = 0,
    val paidSteps: Int = 0,
) {
    val shown: List<HintStep> get() = steps.take(nextStepIndex)
    val stage: HintStage? get() = shown.lastOrNull()?.stage

    /** Старые тексты удаляются; купленные позиции и ответ остаются открытыми.
     * Будущие позиции берутся сначала из прежнего порядка, затем из стабильной
     * перестановки. Миграция не меняет ни один счётчик и не использует Random.Default.
     */
    internal fun lettersOnly(task: CampaignTask?, known: Set<Int> = emptySet()): HintLadder {
        val target = target ?: return this
        if (letterLadderVersion >= 1 && revealedPositions.containsAll(known)) return this
        val word = target.word
        val acquired = shown
        val acquiredPositions = acquired.flatMap { positionsOf(word, it) }.toSet()
        val baseline = knownPositions(word, task) + (known - acquiredPositions) + (revealedPositions - acquiredPositions)
        var positions = baseline
        val retained = mutableListOf<HintStep>()
        for (step in acquired) {
            if (step.stage !in letterStages) continue
            val next = positions + positionsOf(word, step)
            if (next == positions) continue
            positions = next
            retained += letterStep(word, positions)
        }
        val open = retained.size
        val order = steps.drop(nextStepIndex).filter { it.stage != HintStage.ANSWER }
            .flatMap { positionsOf(word, it).sorted() }.distinct() +
            word.indices.shuffled(Random(word.hashCode()))
        appendLetters(word, target, positions, order, retained)
        return copy(steps = retained, nextStepIndex = open, revealedPositions = positions,
            letterLadderVersion = 1, paidSteps = paidSteps)
    }

    companion object {
        private val letterStages = setOf(HintStage.FIRST_LETTER, HintStage.PATTERN, HintStage.ANSWER)

        internal fun build(target: HintTarget, task: CampaignTask?, data: HintWordData, random: Random): HintLadder {
            val word = target.word
            val known = knownPositions(word, task) + data.knownPositions.filter { it in word.indices }
            val steps = mutableListOf<HintStep>()
            appendLetters(word, target, known, word.indices.shuffled(random), steps)
            return HintLadder(target, steps, revealedPositions = known, letterLadderVersion = 1)
        }

        private fun knownPositions(word: String, task: CampaignTask?): Set<Int> = when (task) {
            is CampaignTask.T2 -> setOf(0)
            is CampaignTask.T5 -> (word.length - task.ending.length until word.length).toSet()
            else -> emptySet()
        }

        private fun positionsOf(word: String, step: HintStep): Set<Int> = when (step.stage) {
            HintStage.ANSWER -> word.indices.toSet()
            HintStage.FIRST_LETTER -> step.revealedPositions.ifEmpty { setOf(0) }
            HintStage.PATTERN -> step.revealedPositions + word.indices.filter { i ->
                step.text?.getOrNull(i) == word[i]
            }
            else -> emptySet()
        }.filter { it in word.indices }.toSet()

        private fun letterStep(word: String, positions: Set<Int>): HintStep {
            val stage = when {
                positions.size == word.length -> HintStage.ANSWER
                positions == setOf(0) -> HintStage.FIRST_LETTER
                else -> HintStage.PATTERN
            }
            return HintStep(stage, word.mapIndexed { i, char -> if (i in positions) char else '_' }.joinToString(""),
                revealedPositions = positions)
        }

        private fun appendLetters(word: String, target: HintTarget, known: Set<Int>, order: List<Int>, steps: MutableList<HintStep>) {
            var positions = known
            val remaining = order.distinct().filter { it !in positions }.toMutableList()
            // Начальная буква — первый ориентир, затем приоритет буквы задания T7.
            if (0 in remaining) { remaining.remove(0); remaining.add(0, 0) }
            val taskPositions = remaining.filter { word[it] == target.letter }
            val first = remaining.take(1).filter { it == 0 }
            val sorted = first + taskPositions.filter { it !in first } + remaining.filter { it !in first && it !in taskPositions }
            for (position in sorted) {
                positions = positions + position
                steps += letterStep(word, positions)
            }
        }
    }
}

/** Счётчики за весь уровень не сбрасываются при смене цели. Все поля совместимы со старым сохранением. */
data class HintState(
    val ladder: HintLadder = HintLadder(),
    val shownSteps: Int = 0,
    val paidUnits: Int = 0,
    val preferredTaskId: String? = null,
) {
    /** Upgrade to owned hints: keep target, counters and every revealed letter. */
    fun withoutLegacyDefinitions(): HintState {
        val retained = ladder.steps.filter { it.stage != HintStage.DEFINITION }
        val shown = ladder.steps.take(ladder.nextStepIndex).count { it.stage != HintStage.DEFINITION }
        return copy(ladder = ladder.copy(steps = retained, nextStepIndex = shown))
    }
}
