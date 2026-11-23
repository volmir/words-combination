package com.example.sostavslova.core

/** Занятость плиток по буквам ввода, независимо от истории нажатий. */
object TileUsage {
    /**
     * [order] — перестановка индексов [word], например результат [LetterOrder.shuffled].
     * Для каждой буквы занимает первые k плиток в порядке отображения.
     * Нормализация сохраняет исходные индексы плиток и сворачивает регистр и ё/е.
     * Избыточные и отсутствующие в слове буквы ввода не создают новых плиток.
     */
    fun usedMask(word: String, order: List<Int>, input: String): List<Boolean> {
        val remaining = Alphabet.normalize(input).groupingBy { it }.eachCount().toMutableMap()
        return order.map { index ->
            val letter = Alphabet.normalize(word[index].toString()).singleOrNull()
            val count = remaining[letter] ?: 0
            if (letter != null && count > 0) {
                remaining[letter] = count - 1
                true
            } else {
                false
            }
        }
    }

    /** Есть ли хотя бы одна свободная плитка в порядке отображения. */
    fun canTake(word: String, order: List<Int>, input: String): Boolean =
        usedMask(word, order, input).any { used -> !used }
}
