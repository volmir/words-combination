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
)

/** Контекст назначения сохраняется до угадывания, даже если задание уже закрыто другим ответом. */
data class HintTarget(
    val word: String = "",
    val taskId: String? = null,
    val taskType: TaskType? = null,
    val letter: Char? = null,
)

/** Только данные шага; подписи, длина со склонением и сообщения форматируются в UI. */
data class HintStep(
    val stage: HintStage = HintStage.LENGTH,
    val text: String? = null,
    val length: Int? = null,
    val revealedPositions: Set<Int> = emptySet(),
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
) {
    val shown: List<HintStep> get() = steps.take(nextStepIndex)
    val stage: HintStage? get() = shown.lastOrNull()?.stage

    companion object {
        internal fun build(target: HintTarget, task: CampaignTask?, data: HintWordData, random: Random): HintLadder {
            val word = target.word
            val steps = mutableListOf<HintStep>()
            val texts = mutableSetOf<String>()
            val knownPositions = when (task) {
                is CampaignTask.T2 -> setOf(0)
                is CampaignTask.T5 -> (word.length - task.ending.length until word.length).toSet()
                else -> emptySet()
            }
            var positions = knownPositions
            fun addText(stage: HintStage, text: String?) {
                if (text != null && texts.add(Alphabet.normalize(text))) steps += HintStep(stage, text)
            }
            val category = data.category
            val categoryText = category?.text?.let { safeCategory(word, it, data) }
            if (task is CampaignTask.T6 && category?.key == task.category) {
                categoryText?.let { texts += Alphabet.normalize(it) }
            } else {
                addText(HintStage.THEME, categoryText)
            }
            addText(HintStage.DEFINITION, data.definition?.let {
                DefinitionHint.prepare(word, it, data.forms, data.related)
            })
            steps += HintStep(HintStage.LENGTH, length = word.length)
            if (0 !in positions) {
                positions = positions + 0
                steps += HintStep(HintStage.FIRST_LETTER, word.first().toString(), revealedPositions = positions)
            }
            // Каждая новая позиция — отдельный шаг; одинаковые буквы в других местах остаются скрыты.
            while (positions.size < word.length / 2) {
                val hidden = word.indices.filter { it !in positions }
                val taskLetter = hidden.filter { word[it] == target.letter }
                val position = (taskLetter.ifEmpty { hidden }).random(random)
                positions = positions + position
                val pattern = word.mapIndexed { i, char -> if (i in positions) char else '_' }.joinToString("")
                steps += HintStep(HintStage.PATTERN, pattern, revealedPositions = positions)
            }
            steps += HintStep(HintStage.ANSWER, word, revealedPositions = word.indices.toSet())
            return HintLadder(target, steps, revealedPositions = knownPositions)
        }

        private fun safeCategory(word: String, text: String, data: HintWordData): String? {
            val clean = HintText.clean(text) ?: return null
            val normalized = Alphabet.normalize(clean)
            if (normalized.isEmpty()) return null
            val family = (data.forms + data.related + word).map(Alphabet::normalize).filter { it.isNotEmpty() }
            val tokens = clean.split(Regex("[^А-Яа-яЁё\\u0300\\u0301]+"))
                .map(Alphabet::normalize).filter { it.isNotEmpty() }
            // Для короткой темы вместо малоинформативного «***» пропускаем весь шаг.
            if (family.any { relative -> normalized.contains(relative) || tokens.any {
                    DefinitionHint.sharesRoot(relative, it) ||
                        // Короткую тему безопаснее пропустить даже при неоднозначном корне
                        // (например, известное родство «кошка» и тема «кошачьи»).
                        (relative.length >= 3 && it.length >= 3 && relative.take(3) == it.take(3))
                } }
            ) {
                return null
            }
            return clean
        }
    }
}

/** Счётчики за весь уровень не сбрасываются при смене цели. Все поля совместимы со старым сохранением. */
data class HintState(
    val ladder: HintLadder = HintLadder(),
    val shownSteps: Int = 0,
    val paidUnits: Int = 0,
)
