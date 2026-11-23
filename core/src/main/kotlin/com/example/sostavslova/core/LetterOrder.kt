package com.example.sostavslova.core

/** Порядок плиток хранит индексы букв в исходном слове, включая повторяющиеся. */
object LetterOrder {
    fun natural(word: String): List<Int> = word.indices.toList()

    /**
     * Fisher–Yates с собственным 64-битным линейным конгруэнтным генератором.
     * Одинаковые аргументы дают одинаковый порядок на JVM и Android.
     * При совпадении с [previous] сдвигает индексы влево на одну позицию.
     */
    fun shuffled(word: String, seed: Long, previous: List<Int>): List<Int> {
        val order = natural(word).toMutableList()
        var state = seed
        for (i in order.lastIndex downTo 1) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val j = ((state ushr 1) % (i + 1).toLong()).toInt()
            val item = order[i]
            order[i] = order[j]
            order[j] = item
        }
        return if (order.size > 1 && order == previous) {
            order.drop(1) + order.first()
        } else {
            order.toList()
        }
    }
}
