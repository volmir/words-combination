package com.example.sostavslova.core

/** Единые параметры экономики подсказок v2. */
object HintEconomy {
    const val earnEvery: Int = 4
    const val stepCost: Int = 1
    const val t4FirstStepFree: Boolean = true

    fun initialBudget(stage: Int): Int = when (stage) {
        1, 2 -> 5
        3, 4 -> 4
        5, 6 -> 3
        else -> 3
    }
}
