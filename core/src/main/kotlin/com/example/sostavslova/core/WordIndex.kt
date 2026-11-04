package com.example.sostavslova.core

/**
 * Индекс словаря в памяти. Для каждого слова хранит
 *  - битовую маску использованных букв (32 буквы = 32 бита Int),
 *  - счётчики букв (32 байта на слово).
 *
 * Поиск слов, которые можно составить из исходного слова, - это проверка каждого слова словаря
 * на вхождение в мультимножество букв исходного слова. Для ~45 000 слов это единицы миллисекунд;
 * никакого перебора размещений (как в оригинале) не требуется.
 *
 * Потокобезопасен после создания (только чтение).
 */
class WordIndex(val words: List<String>) {

    private val masks = IntArray(words.size)
    private val counts = ByteArray(words.size * Alphabet.SIZE)

    init {
        for (w in words.indices) {
            val word = words[w]
            var mask = 0
            val base = w * Alphabet.SIZE
            for (ch in word) {
                val i = Alphabet.indexOf(ch)
                check(i >= 0) { "Недопустимый символ '$ch' в слове '$word'" }
                mask = mask or (1 shl i)
                counts[base + i] = (counts[base + i] + 1).toByte()
            }
            masks[w] = mask
        }
    }

    val size: Int get() = words.size

    /**
     * Все слова словаря, составимые из букв [source] (каждая буква не чаще, чем в [source]).
     * [source] должен быть уже нормализован ([Alphabet.normalize]).
     * Само исходное слово в результат не входит. Порядок - как в словаре.
     */
    fun findBuildable(source: String): List<String> {
        val srcCounts = IntArray(Alphabet.SIZE)
        var srcMask = 0
        for (ch in source) {
            val i = Alphabet.indexOf(ch)
            if (i < 0) continue
            srcCounts[i]++
            srcMask = srcMask or (1 shl i)
        }
        val forbidden = srcMask.inv()
        val result = ArrayList<String>()
        for (w in words.indices) {
            if ((masks[w] and forbidden) != 0) continue
            val base = w * Alphabet.SIZE
            var ok = true
            for (i in 0 until Alphabet.SIZE) {
                if (counts[base + i].toInt() > srcCounts[i]) {
                    ok = false
                    break
                }
            }
            if (ok && words[w] != source) result.add(words[w])
        }
        return result
    }

    fun contains(word: String): Boolean = wordSet.contains(word)

    private val wordSet: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { words.toHashSet() }

    companion object {
        /** Читает words.txt: по слову в строке, пустые строки пропускаются. */
        fun fromText(text: String): WordIndex =
            WordIndex(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
    }
}

/** Результат поиска: слова отсортированы по длине, затем по алфавиту. */
class SearchResult(val source: String, words: List<String>) {
    val words: List<String> = words.sortedWith(compareBy({ it.length }, { it }))
    val total: Int get() = words.size

    /** Длина слова -> слова этой длины (ключи по возрастанию). */
    val byLength: Map<Int, List<String>> by lazy(LazyThreadSafetyMode.NONE) {
        this.words.groupBy { it.length }.toSortedMap()
    }
}

class WordSearch(private val index: WordIndex) {
    /** [raw] - ввод пользователя; возвращает null, если ввод некорректен (см. [parseWordInput]). */
    fun search(raw: String): SearchResult? {
        val input = parseWordInput(raw) as? WordInput.Ok ?: return null
        return SearchResult(input.word, index.findBuildable(input.word))
    }

    fun searchNormalized(source: String): SearchResult =
        SearchResult(source, index.findBuildable(source))
}
