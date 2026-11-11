package com.example.sostavslova.qa

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.WordIndex
import com.example.sostavslova.data.Definition
import com.example.sostavslova.data.DefinitionsRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.di.AppContainer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/** Только синтетические данные; ни assets, ни пользовательский progress не используются. */
internal object QaCampaign {
    val words = listOf("кот", "крот", "торт", "карта", "река", "трек", "рак", "рот")
    val json = """
        {"version":2,"countMinLength":4,
         "stages":[{"number":1,"firstLevel":1,"lastLevel":1},
                   {"number":2,"firstLevel":2,"lastLevel":2},
                   {"number":3,"firstLevel":3,"lastLevel":3}],
         "levels":[
          {"number":1,"word":"картотека","stage":1,"requiredStars":1,"tasks":[
           {"id":"count1","type":"T1","n":2},
           {"id":"initial1","type":"T2","letter":"к","n":1},
           {"id":"riddle1","type":"T4","targets":["кот"]}]},
          {"number":2,"word":"электротрактор","stage":2,"requiredStars":1,"tasks":[
           {"id":"long2","type":"T3","minLength":4,"hintTarget":"трек"},
           {"id":"ending2","type":"T5","ending":"от","minLength":3}]},
          {"number":3,"word":"электрокарта","stage":3,"requiredStars":2,"tasks":[
           {"id":"theme3","type":"T6","category":"animals","n":2,"words":["кот","крот","рак"]},
           {"id":"contains3","type":"T7","letter":"р","n":1},
           {"id":"count3","type":"T1","n":3}]}]}
    """.trimIndent()

    enum class Meanings { SAFE, ABSENT, FILTERED }

    fun container(file: File, meanings: Meanings = Meanings.SAFE, words: WordRepository? = null): AppContainer =
        AppContainer(InstrumentationRegistry.getInstrumentation().targetContext,
            readCampaign = { json },
            words = words ?: WordRepository({ this.words.joinToString("\n") }),
            definitions = object : DefinitionsRepository {
                override suspend fun isAvailable() = meanings != Meanings.ABSENT
                override suspend fun categoryFor(word: String) =
                    if (meanings == Meanings.SAFE) "animals" else null
                override suspend fun definitionsFor(word: String): List<Definition> = when (meanings) {
                    Meanings.ABSENT -> error("Unavailable database must not be read")
                    Meanings.FILTERED -> listOf(Definition(word, word))
                    Meanings.SAFE -> listOf(Definition(word,
                        "Синтетическое существо для проверки игрового сценария.", origin = "wiktionary"))
                }
            },
            progressFile = { file },
        ).also { it.random = Random(42) }
}

internal suspend fun AppContainer.closeQa() {
    if (applicationScope.coroutineContext.job.isActive) progressRepository.awaitSaved()
    applicationScope.cancel()
    applicationScope.coroutineContext.job.join()
}

/** Ловит перебор на Main детерминированно, независимо от скорости устройства. */
internal class DictionaryProbe(private val values: List<String>) : AbstractList<String>() {
    val reads = AtomicInteger()
    val mainReads = AtomicInteger()
    val lastReadIndex = AtomicInteger(-1)
    private fun accessed() {
        reads.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet()
    }
    override val size: Int get() { accessed(); return values.size }
    override fun get(index: Int): String {
        accessed()
        lastReadIndex.updateAndGet { maxOf(it, index) }
        return values[index]
    }
    fun index(): WordIndex = WordIndex(this)
}
