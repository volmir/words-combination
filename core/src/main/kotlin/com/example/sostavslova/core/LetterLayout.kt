package com.example.sostavslova.core

/**
 * Равномерно распределяет [count] ячеек по строкам, не превышающим [maxPerRow].
 * Соседние строки различаются не более чем на одну ячейку: 13 при восьми
 * допустимых ячейках — это 7+6, а не 8+5.
 */
fun balancedRows(count: Int, maxPerRow: Int): List<Int> {
    require(count >= 0) { "count must be non-negative" }
    require(maxPerRow > 0) { "maxPerRow must be positive" }
    if (count == 0) return emptyList()
    val rows = (count + maxPerRow - 1) / maxPerRow
    val base = count / rows
    val extra = count % rows
    return List(rows) { index -> base + if (index < extra) 1 else 0 }
}
