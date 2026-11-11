package com.example.sostavslova.data

data class Definition(
    val display: String,
    val text: String,
    val notes: String = "",
    val antonym: String = "",
    val example: String = "",
    /** URL статьи, история которой содержит авторство (пусто у старых баз). */
    val source: String = "",
    /** Наблюдаемые формы и ближайшее родство для обработки подсказки в core. */
    val forms: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val origin: String = "",
) {
    val isWiktionary: Boolean get() = origin == "wiktionary" || source.startsWith("https://ru.wiktionary.org/wiki/")
}

interface DefinitionsRepository {
    suspend fun definitionsFor(word: String): List<Definition>
    suspend fun isAvailable(): Boolean
    /** Ключ известной содержательной рубрики; other и старая БД дают null. */
    suspend fun categoryFor(word: String): String? = null
}
