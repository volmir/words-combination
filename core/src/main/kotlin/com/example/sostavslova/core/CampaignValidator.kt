package com.example.sostavslova.core

/** Машинный адрес и диагностическая причина; это не локализованная строка интерфейса. */
data class CampaignValidationError(
    val field: String,
    val reason: String,
    val levelNumber: Int? = null,
    val taskId: String? = null,
) {
    override fun toString(): String = "level=$levelNumber task=$taskId $field: $reason"
}

/** Структура и явные назначения; не требует полного словаря, БД или 30 уровней. */
object CampaignValidator {
    fun validate(campaign: Campaign): List<CampaignValidationError> = buildList {
        fun error(field: String, reason: String, level: CampaignLevel? = null, task: CampaignTask? = null) {
            add(CampaignValidationError(field, reason, level?.number, task?.id))
        }
        if (campaign.version != 2) error("version", "Expected version 2")
        if (campaign.countMinLength != 4) error("countMinLength", "Expected 4")
        if (campaign.stages.isEmpty()) error("stages", "Must not be empty")
        if (campaign.levels.isEmpty()) error("levels", "Must not be empty")
        val stages = mutableSetOf<Int>()
        for (stage in campaign.stages) {
            if (stage.number <= 0 || !stages.add(stage.number)) error("stages.number", "Invalid or duplicate stage ${stage.number}")
            if (stage.firstLevel <= 0 || stage.lastLevel < stage.firstLevel) error("stages.range", "Invalid range for stage ${stage.number}")
        }
        for ((previous, next) in campaign.stages.sortedBy { it.firstLevel }.zipWithNext()) {
            if (previous.lastLevel >= next.firstLevel) error("stages.range", "Stage ranges overlap: ${previous.number}/${next.number}")
        }
        val numbers = mutableSetOf<Int>()
        val words = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val targets = mutableSetOf<String>()
        val endings = mutableSetOf<String>()
        val categories = mutableSetOf<String>()
        for ((position, level) in campaign.levels.withIndex()) {
            if (!numbers.add(level.number) || level.number != position + 1) error("number", "Expected unique ordered number ${position + 1}", level)
            if (!word(level.word) || !words.add(Alphabet.normalize(level.word))) error("word", "Expected unique canonical word", level)
            val stage = campaign.stages.singleOrNull { it.number == level.stage }
            if (stage == null || level.number !in stage.firstLevel..stage.lastLevel) error("stage", "Missing or inconsistent stage reference", level)
            if (level.maxStars !in 1..5) error("tasks", "M must be in 1..5", level)
            if (level.requiredStars !in 1..level.maxStars) error("requiredStars", "R must be in 1..M", level)
            val types = mutableSetOf<TaskType>()
            for (task in level.tasks) {
                fun bad(field: String, reason: String) = error(field, reason, level, task)
                fun count(n: Int) { if (n <= 0) bad("n", "Must be positive") }
                fun letter(c: Char) { if (Alphabet.indexOf(c) < 0) bad("letter", "Expected canonical Alphabet letter") }
                fun length(n: Int) { if (n !in Alphabet.MIN_WORD_LENGTH..level.word.length) bad("minLength", "Outside source word length bounds") }
                fun answer(value: String, field: String, assigned: Boolean) {
                    if (!word(value)) {
                        bad(field, "Expected canonical game word: $value")
                    } else if (WordIndex(listOf(value)).findBuildable(level.word).isEmpty()) {
                        bad(field, "Not a distinct buildable answer: $value")
                    }
                    if (assigned && !targets.add(Alphabet.normalize(value))) bad(field, "Assigned T3/T4 target repeats: $value")
                }
                if (task.id.isBlank() || task.id != task.id.trim() || !ids.add(task.id)) bad("id", "Expected nonblank globally unique ID")
                if (!types.add(task.type)) bad("type", "Task type repeats within level")
                when (task) {
                    is CampaignTask.T1 -> count(task.n)
                    is CampaignTask.T2 -> { letter(task.letter); count(task.n) }
                    is CampaignTask.T3 -> {
                        length(task.minLength)
                        answer(task.hintTarget, "hintTarget", true)
                        if (task.hintTarget.length < task.minLength) bad("hintTarget", "Shorter than minLength")
                    }
                    is CampaignTask.T4 -> {
                        if (task.targets.isEmpty()) bad("targets", "Must not be empty")
                        task.targets.forEach { answer(it, "targets", true) }
                    }
                    is CampaignTask.T5 -> {
                        length(task.minLength)
                        if (task.ending.isEmpty() || task.ending != Alphabet.normalize(task.ending) || task.ending.length > level.word.length) bad("ending", "Expected nonempty canonical ending within source length")
                        if (!endings.add(Alphabet.normalize(task.ending))) bad("ending", "Ending repeats across campaign")
                    }
                    is CampaignTask.T6 -> {
                        count(task.n)
                        if (task.category.isBlank() || task.category != task.category.trim()) bad("category", "Expected category key")
                        if (!categories.add(task.category)) bad("category", "Category repeats across campaign")
                        if (task.words.isEmpty()) bad("words", "Must not be empty")
                        if (task.words.map(Alphabet::normalize).distinct().size != task.words.size) bad("words", "Local answers repeat")
                        task.words.forEach { answer(it, "words", false) }
                        if (task.n > task.words.distinct().size) bad("n", "Exceeds local answer count")
                    }
                    is CampaignTask.T7 -> { letter(task.letter); count(task.n) }
                }
            }
        }
    }

    private fun word(value: String): Boolean =
        value.length in Alphabet.MIN_WORD_LENGTH..Alphabet.MAX_WORD_LENGTH && value == Alphabet.normalize(value)
}

/** Единственная версия производственного профиля, которую принимает валидатор. */
enum class ProductionCampaignProfile(val id: String) {
    RELEASE_30("release-30"),
    SHORT15_RELEASE_V1("short15-release-v1"),
}

/**
 * Производственное расписание; структурные мини-кампании его не требуют.
 * Без профиля сохраняется исторический 30/100-контракт, а short15 включается
 * только явным ID профиля (тем же, что в tools/campaign_profiles.json).
 */
object ProductionCampaignValidator {
    fun validate(campaign: Campaign): List<CampaignValidationError> =
        validate(campaign, ProductionCampaignProfile.RELEASE_30)

    fun validate(campaign: Campaign, profile: ProductionCampaignProfile): List<CampaignValidationError> = when (profile) {
        ProductionCampaignProfile.RELEASE_30 -> Release30CampaignValidator.validate(campaign)
        ProductionCampaignProfile.SHORT15_RELEASE_V1 -> Short15CampaignValidator.validate(campaign)
    }

    fun validate(campaign: Campaign, profileId: String?): List<CampaignValidationError> = when (profileId) {
        null, "", ProductionCampaignProfile.RELEASE_30.id ->
            validate(campaign, ProductionCampaignProfile.RELEASE_30)
        ProductionCampaignProfile.SHORT15_RELEASE_V1.id ->
            validate(campaign, ProductionCampaignProfile.SHORT15_RELEASE_V1)
        else -> listOf(CampaignValidationError("profile", "Unknown production profile: $profileId"))
    }
}

/** Исторический синтетический 30/100 профиль; остаётся доступен тестам. */
internal object Release30CampaignValidator {
    fun validate(campaign: Campaign): List<CampaignValidationError> = buildList {
        addAll(CampaignValidator.validate(campaign))
        fun error(field: String, reason: String, level: CampaignLevel? = null) {
            add(CampaignValidationError(field, reason, level?.number))
        }
        if (campaign.levels.size != 30) error("levels.size", "Expected 30 levels")
        if (campaign.maxStars != 100) error("maxStars", "Expected 100 stars")
        if (campaign.stages.size != 6) error("stages.size", "Expected 6 stages")
        for (number in 1..6) {
            val stage = campaign.stages.singleOrNull { it.number == number }
            if (stage != Stage(number, (number - 1) * 5 + 1, number * 5)) error("stages.range", "Expected five-level range for stage $number")
        }
        val thresholds = listOf(1, 1, 2, 2, 3, 3)
        for (level in campaign.levels) {
            if (level.number !in 1..30) continue
            val stage = (level.number - 1) / 5 + 1
            val m = minOf(stage, 5)
            val r = if (level.number == 30) 4 else thresholds[stage - 1]
            if (level.stage != stage) error("stage", "Expected stage $stage", level)
            if (level.maxStars != m) error("tasks", "Expected M=$m", level)
            if (level.requiredStars != r) error("requiredStars", "Expected R=$r", level)
            if (stage < 6) {
                val expected = TaskType.entries.take(stage).toSet()
                if (level.tasks.map { it.type }.toSet() != expected) error("tasks.types", "Unexpected stage types", level)
                for (task in level.tasks.filter { it.type !in expected }) {
                    add(CampaignValidationError("type", "Type not allowed in stage $stage", level.number, task.id))
                }
            }
        }
        val masterLevels = campaign.levels.filter { it.stage == 6 }
        val masterTypes = masterLevels.flatMap { it.tasks }.map { it.type }.toSet()
        if (TaskType.T6 !in masterTypes || TaskType.T7 !in masterTypes) error("tasks.types", "Master stage must include T6 and T7")
        if (masterLevels.map { level -> level.tasks.map { it.type }.toSet() }.distinct().size < 2) error("tasks.types", "Master stage must rotate types")
    }
}

/**
 * Production short15 profile: 15 levels and 6 stages remain fixed, task composition
 * is flexible. Structural and parameter admissibility is enforced here; reachability
 * and dictionary-backed checks remain in GEN-04/GEN-05 and dataset audit.
 */
object Short15CampaignValidator {
    private val stageRanges = listOf(1 to 3, 4 to 5, 6 to 8, 9 to 10, 11 to 13, 14 to 15)

    fun validate(campaign: Campaign): List<CampaignValidationError> = buildList {
        addAll(CampaignValidator.validate(campaign))
        fun error(field: String, reason: String, level: CampaignLevel? = null, task: CampaignTask? = null) {
            add(CampaignValidationError(field, reason, level?.number, task?.id))
        }
        if (campaign.levels.size != 15) error("levels.size", "Expected 15 levels")
        if (campaign.stages.size != 6) error("stages.size", "Expected 6 stages")
        for ((index, range) in stageRanges.withIndex()) {
            val number = index + 1
            val stage = campaign.stages.singleOrNull { it.number == number }
            if (stage != Stage(number, range.first, range.second))
                error("stages.range", "Expected stage $number range ${range.first}..${range.second}")
        }
        if (campaign.maxStars != campaign.levels.sumOf { it.tasks.size })
            error("maxStars", "Total stars must equal the sum of level tasks")
        for (level in campaign.levels) {
            val expectedStage = stageRanges.indexOfFirst { level.number in it.first..it.second } + 1
            if (expectedStage <= 0) {
                error("schedule", "No short15 stage range for level ${level.number}", level)
                continue
            }
            if (level.stage != expectedStage) error("stage", "Expected stage $expectedStage", level)
            if (level.word.length !in 8..13) error("word", "Short15 source must be 8..13 letters", level)
            if (level.tasks.size !in 1..5) error("tasks", "M must be in 1..5", level)
            if (level.requiredStars !in 1..level.tasks.size) error("requiredStars", "R must be in 1..M", level)
            if (level.maxStars != level.tasks.size) error("tasks", "M must equal task count", level)
        }
    }
}
