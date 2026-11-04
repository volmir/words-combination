package com.example.sostavslova.core

/**
 * Уровень («Прохождение»). Три задания = три звезды:
 *  1. пройти уровень: отгадать не менее [answers] слов;
 *  2. отгадать не менее [words] слов;
 *  3. отгадать не менее [letterCount] слов на букву [letter].
 */
data class Level(
    val index: Int,
    val word: String,
    val answers: Int,
    val words: Int,
    val letter: Char,
    val letterCount: Int,
)

/** Звёзды уровня. Раз полученная звезда не пропадает (как в оригинале). */
data class LevelProgress(
    val completed: Boolean = false,
    val wordsTask: Boolean = false,
    val lettersTask: Boolean = false,
) {
    val stars: Int get() = listOf(completed, wordsTask, lettersTask).count { it }

    infix fun merge(other: LevelProgress) = LevelProgress(
        completed = completed || other.completed,
        wordsTask = wordsTask || other.wordsTask,
        lettersTask = lettersTask || other.lettersTask,
    )
}

object LevelRules {

    fun lettersCount(level: Level, found: List<String>): Int =
        found.count { it.isNotEmpty() && it[0] == level.letter }

    /** Прогресс по текущему набору найденных слов (без учёта ранее полученных звёзд). */
    fun evaluate(level: Level, found: List<String>): LevelProgress = LevelProgress(
        completed = found.size >= level.answers,
        wordsTask = found.size >= level.words,
        lettersTask = lettersCount(level, found) >= level.letterCount,
    )

    /** Сколько слов осталось до прохождения уровня (0, если пройден). */
    fun remainingToComplete(level: Level, found: List<String>): Int =
        (level.answers - found.size).coerceAtLeast(0)

    /**
     * Первый уровень доступен всегда; каждый следующий - после прохождения (первая звезда) предыдущего.
     * [progress] - ключ: слово уровня.
     */
    fun isUnlocked(levels: List<Level>, progress: Map<String, LevelProgress>, position: Int): Boolean {
        if (position <= 0) return true
        if (position >= levels.size) return false
        return progress[levels[position - 1].word]?.completed == true
    }
}
