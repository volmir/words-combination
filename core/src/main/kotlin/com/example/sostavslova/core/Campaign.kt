package com.example.sostavslova.core

/** Данные v2. JSON декодируется и проверяется на границе :app; v1 в Levels.kt независима. */
data class Campaign(
    val stages: List<Stage>,
    val levels: List<CampaignLevel>,
    val version: Int = 2,
    val countMinLength: Int = 4,
) {
    val maxStars: Int get() = levels.sumOf { it.maxStars }
}

/** Номер — ключ локализованного названия этапа в UI, границы включительны. */
data class Stage(val number: Int, val firstLevel: Int, val lastLevel: Int)

/** [word] — стабильный ключ сохранений; порядок [tasks] определяет порядок показа. */
data class CampaignLevel(
    val number: Int,
    val word: String,
    val stage: Int,
    val requiredStars: Int,
    val tasks: List<CampaignTask>,
) {
    /** M выводится из заданий и не может разойтись с их числом. */
    val maxStars: Int get() = tasks.size
}

enum class TaskType { T1, T2, T3, T4, T5, T6, T7 }

/** Тип выводится из класса параметров: несоответствие типа и параметров невозможно. */
sealed interface CampaignTask {
    val id: String
    val type: TaskType

    data class T1(override val id: String, val n: Int) : CampaignTask {
        override val type: TaskType get() = TaskType.T1
    }
    data class T2(override val id: String, val letter: Char, val n: Int) : CampaignTask {
        override val type: TaskType get() = TaskType.T2
    }
    /** Любой ответ minLength+, hintTarget резервируется только для подсказки. */
    data class T3(override val id: String, val minLength: Int, val hintTarget: String) : CampaignTask {
        override val type: TaskType get() = TaskType.T3
    }
    /** Все фиксированные цели вместе составляют одно задание и одну звезду. */
    data class T4(override val id: String, val targets: List<String>) : CampaignTask {
        override val type: TaskType get() = TaskType.T4
    }
    data class T5(override val id: String, val ending: String, val minLength: Int) : CampaignTask {
        override val type: TaskType get() = TaskType.T5
    }
    /** category — ключ, а не подпись; words — полный локальный набор без зависимости от БД. */
    data class T6(override val id: String, val category: String, val n: Int, val words: List<String>) : CampaignTask {
        override val type: TaskType get() = TaskType.T6
    }
    data class T7(override val id: String, val letter: Char, val n: Int) : CampaignTask {
        override val type: TaskType get() = TaskType.T7
    }
}

/** Данные открытого уровня; без вычисления звёзд, доступа или политики подсказок. */
data class CurrentCampaignLevel(
    val level: CampaignLevel,
    val game: GameState,
    val completedTaskIds: Set<String> = emptySet(),
    val hints: HintState = HintState(),
)
