package com.example.sostavslova.data

data class Definition(
    val display: String,
    val text: String,
    val notes: String = "",
    val antonym: String = "",
    val example: String = "",
    /** URL статьи, история которой содержит авторство (пусто у старых баз). */
    val source: String = "",
)

interface DefinitionsRepository {
    suspend fun definitionsFor(word: String): List<Definition>
    suspend fun isAvailable(): Boolean
}
