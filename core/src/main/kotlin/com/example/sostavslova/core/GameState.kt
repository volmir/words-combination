package com.example.sostavslova.core

import kotlin.math.roundToInt
import kotlin.random.Random

enum class AnswerResult {
    /** Слово найдено и засчитано («Правильно!»). */
    CORRECT,

    /** Слово уже было отгадано. */
    ALREADY_FOUND,

    /** Слова нет среди возможных («Слово ... не найдено!»). */
    NOT_FOUND,

    /** Пользователь ввёл само исходное слово. */
    SAME_AS_SOURCE,

    /** Пустой ввод (после нормализации). */
    EMPTY,

    /** Короче 3 букв. */
    TOO_SHORT,
}

data class Submission(val state: GameState, val result: AnswerResult)

data class LengthStat(val length: Int, val found: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else (found * 100.0 / total).roundToInt()
}

data class Statistics(val rows: List<LengthStat>) {
    val totalFound: Int get() = rows.sumOf { it.found }
    val totalWords: Int get() = rows.sumOf { it.total }
    val totalPercent: Int get() = if (totalWords == 0) 0 else (totalFound * 100.0 / totalWords).roundToInt()
}

/**
 * Состояние одной игры. Неизменяемо: submit() возвращает новое состояние.
 *
 * @param word      исходное слово (нормализованное)
 * @param allWords  все слова, которые можно составить (от 3 букв), отсортированные
 * @param found     найденные игроком слова в порядке нахождения
 */
data class GameState(
    val word: String,
    val allWords: List<String>,
    val found: List<String> = emptyList(),
) {
    private val allSet: Set<String> by lazy(LazyThreadSafetyMode.NONE) { allWords.toHashSet() }
    private val foundSet: Set<String> by lazy(LazyThreadSafetyMode.NONE) { found.toHashSet() }

    val total: Int get() = allWords.size
    val foundCount: Int get() = found.size

    /** Игра окончена, если найдены все слова (и слова вообще существуют). */
    val isFinished: Boolean get() = allWords.isNotEmpty() && found.size == allWords.size

    /** Для исходных слов, из которых нельзя составить ни одного слова. */
    val hasNoWords: Boolean get() = allWords.isEmpty()

    fun submit(raw: String): Submission {
        val answer = Alphabet.normalize(raw)
        val result = when {
            answer.isEmpty() -> AnswerResult.EMPTY
            answer.length < Alphabet.MIN_WORD_LENGTH -> AnswerResult.TOO_SHORT
            answer == word -> AnswerResult.SAME_AS_SOURCE
            foundSet.contains(answer) -> AnswerResult.ALREADY_FOUND
            allSet.contains(answer) -> AnswerResult.CORRECT
            else -> AnswerResult.NOT_FOUND
        }
        val next = if (result == AnswerResult.CORRECT) copy(found = found + answer) else this
        return Submission(next, result)
    }

    /** Случайное ещё не найденное слово для подсказки (или null, если всё найдено). */
    fun hintWord(random: Random = Random.Default): String? {
        val remaining = allWords.filter { it !in foundSet }
        if (remaining.isEmpty()) return null
        return remaining[random.nextInt(remaining.size)]
    }

    /** Ограниченный набор ненайденных ответов для поиска пригодного толкования. */
    fun hintCandidates(random: Random = Random.Default, limit: Int = 24): List<String> {
        require(limit > 0)
        return allWords.filter { it !in foundSet }.shuffled(random).take(limit.coerceAtMost(24))
    }

    /** Таблица «длина слова -> отгадано / всего / %» и итоговая строка. */
    fun statistics(): Statistics {
        val totals = allWords.groupBy { it.length }.toSortedMap()
        val foundByLen = found.groupingBy { it.length }.eachCount()
        return Statistics(totals.map { (len, list) -> LengthStat(len, foundByLen[len] ?: 0, list.size) })
    }

    companion object {
        /** Начинает игру: все слова длиной от 3 букв, составимые из [word]. */
        fun start(word: String, index: WordIndex, restoredFound: List<String> = emptyList()): GameState {
            val all = SearchResult(word, index.findBuildable(word)).words
                .filter { it.length >= Alphabet.MIN_WORD_LENGTH }
            val allSet = all.toHashSet()
            val valid = restoredFound.filter { it in allSet }.distinct()
            return GameState(word, all, valid)
        }
    }
}
