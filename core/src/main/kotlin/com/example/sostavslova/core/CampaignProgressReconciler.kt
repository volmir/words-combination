package com.example.sostavslova.core

/** Машинные коды преобразований; UI их не показывает, они нужны отчёту и тестам. */
enum class ReconciliationChangeKind {
    CAMPAIGN_ID_UPDATED,
    FOUND_WORDS_FILTERED,
    TASK_STARS_PRESERVED,
    TASK_STARS_RECOMPUTED,
    TASK_REMOVED,
    TASK_COMPLETED_FROM_FOUND,
    TASK_SEMANTICS_CHANGED,
    HINT_LADDER_RESET,
    ACHIEVEMENTS_GRANTED,
    FRONTIER_RAISED,
    LEVEL_RESET_WORD_CHANGED,
    LEVEL_RESET_NUMBER_WITHOUT_HISTORY,
}

data class ReconciliationChange(
    val levelWord: String?,
    val kind: ReconciliationChangeKind,
    val detail: String? = null,
)

/** Уровень, который не удалось перенести адресно; исходный payload сохраняется в архиве. */
data class CampaignLevelReset(val word: String, val reason: String)

data class CampaignReconciliationResult(
    val state: CampaignProgress,
    val changes: List<ReconciliationChange>,
    val resetLevels: List<CampaignLevelReset>,
    /** null — восстановление возможно; иначе причина полного сброса. */
    val incompatibleReason: String? = null,
) {
    val isCompatible: Boolean get() = incompatibleReason == null
}

/**
 * Адресное согласование сохранения с актуальной кампанией. Ключ прогресса —
 * нормализованное исходное слово уровня, а не номер и не SHA файла.
 *
 * Функция чистая: не читает диск, словарь проверяется через переданный [WordIndex],
 * ничего не пишет. Она не угадывает старые слова по новым номерам: для сохранений,
 * адресованных номерами, нужна достоверная карта [historicalLevelWords].
 */
object CampaignProgressReconciler {
    fun reconcile(
        saved: CampaignProgress,
        campaign: Campaign,
        index: WordIndex,
        /** Прежняя конфигурация, если известна: позволяет отличить переиспользованный ID. */
        previousCampaign: Campaign? = null,
        /** Достоверное historic соответствие номера и слова; пустая карта — не угадывать. */
        historicalLevelWords: Map<Int, String> = emptyMap(),
    ): CampaignReconciliationResult {
        require(campaign.levels.isNotEmpty()) { "Campaign must contain levels" }
        val targetId = CampaignIdentity.of(campaign)
        val hintedWords = saved.hintedWords.map(Alphabet::normalize).filter(index::contains).toSet()
        val newByWord = campaign.levels.associateBy { Alphabet.normalize(it.word) }
        require(newByWord.size == campaign.levels.size) { "Campaign source words must be unique" }
        val changes = mutableListOf<ReconciliationChange>()
        val resets = mutableListOf<CampaignLevelReset>()
        if (saved.campaignId != targetId) {
            changes += ReconciliationChange(null, ReconciliationChangeKind.CAMPAIGN_ID_UPDATED, saved.campaignId)
        }

        val addressed = addressSavedLevels(saved.levels, newByWord, historicalLevelWords, previousCampaign, changes, resets)
        if (addressed.isEmpty()) {
            if (saved.levels.isNotEmpty()) {
                return CampaignReconciliationResult(
                    state = CampaignProgress(campaignId = targetId, hintedWords = hintedWords),
                    changes = changes,
                    resetLevels = resets,
                    incompatibleReason = "no-matching-level-words",
                )
            }
            return CampaignReconciliationResult(
                state = CampaignProgress(
                    levels = emptyMap(),
                    highestUnlockedLevel = saved.highestUnlockedLevel.coerceIn(1, campaign.levels.size),
                    finaleReached = saved.finaleReached,
                    finaleShown = saved.finaleShown,
                    lastVisitedLevel = saved.lastVisitedLevel?.takeIf { it in 1..campaign.levels.size },
                    seenExplanations = saved.seenExplanations,
                    campaignId = targetId,
                    hintedWords = hintedWords,
                ),
                changes = changes,
                resetLevels = resets,
            )
        }

        val reconciled = campaign.levels.mapNotNull { level ->
            val old = addressed[Alphabet.normalize(level.word)] ?: return@mapNotNull null
            level.word to reconcileLevel(
                level, old, campaign, index,
                previousCampaign?.levels?.firstOrNull { Alphabet.normalize(it.word) == Alphabet.normalize(level.word) },
                changes,
            )
        }.toMap() + addressed.filterKeys { it !in newByWord }
        val provisional = CampaignProgress(levels = reconciled, campaignId = targetId)
        val frontier = CampaignRules.frontier(campaign, provisional)
        val preservedFrontier = saved.highestUnlockedLevel.coerceIn(1, campaign.levels.size)
        val highest = maxOf(preservedFrontier, frontier)
        if (highest > preservedFrontier) {
            changes += ReconciliationChange(null, ReconciliationChangeKind.FRONTIER_RAISED, "$preservedFrontier->$highest")
        }
        val state = CampaignProgress(
            levels = reconciled,
            highestUnlockedLevel = highest,
            finaleReached = saved.finaleReached,
            finaleShown = saved.finaleShown,
            lastVisitedLevel = saved.lastVisitedLevel?.takeIf { it in 1..campaign.levels.size },
            seenExplanations = saved.seenExplanations,
            campaignId = targetId,
            resetReason = null,
            hintedWords = hintedWords,
        )
        return CampaignReconciliationResult(state, changes, resets)
    }

    /**
     * Приводит ключи к нормализованным словам. Номера без явной исторической карты
     * не отображаются: лучше сохранить исходник в архиве, чем приписать прогресс
     * другому уровню после перестановки.
     */
    private fun addressSavedLevels(
        levels: Map<String, CampaignLevelProgress>,
        newByWord: Map<String, CampaignLevel>,
        historicalLevelWords: Map<Int, String>,
        previousCampaign: Campaign?,
        changes: MutableList<ReconciliationChange>,
        resets: MutableList<CampaignLevelReset>,
    ): Map<String, CampaignLevelProgress> {
        val result = mutableMapOf<String, CampaignLevelProgress>()
        val retired = mutableListOf<Pair<String, CampaignLevelProgress>>()
        for ((rawKey, progress) in levels) {
            // Запись только достижений уже согласована; повторный архив не нужен.
            val achievementsOnly = progress.achievements.isNotEmpty() && progress.found.isEmpty() &&
                progress.completedTaskIds.isEmpty() && progress.solvedRiddleTargets.isEmpty() &&
                progress.hints == HintState()
            val number = rawKey.trim().toIntOrNull()
            val word = if (number != null) {
                historicalLevelWords[number]?.let(Alphabet::normalize)
            } else {
                Alphabet.normalize(rawKey)
            }
            if (number != null && word == null) {
                if (!achievementsOnly) {
                    resets += CampaignLevelReset(rawKey, "number-addressed-level-without-history")
                    changes += ReconciliationChange(rawKey, ReconciliationChangeKind.LEVEL_RESET_NUMBER_WITHOUT_HISTORY)
                }
                retired += rawKey to progress
                continue
            }
            if (word == null || word !in newByWord) {
                if (!achievementsOnly) {
                    resets += CampaignLevelReset(rawKey, "level-word-changed")
                    changes += ReconciliationChange(rawKey, ReconciliationChangeKind.LEVEL_RESET_WORD_CHANGED)
                }
                retired += rawKey to progress
                continue
            }
            val previous = result[word]
            if (previous != null) {
                // Два старых ключа сходятся к одному слову: адресное соответствие неоднозначно.
                resets += CampaignLevelReset(rawKey, "ambiguous-level-word")
                changes += ReconciliationChange(rawKey, ReconciliationChangeKind.LEVEL_RESET_WORD_CHANGED, "ambiguous")
                retired += rawKey to progress
                continue
            }
            result[word] = progress
        }
        // Смена слова сбрасывает ответы и звёзды, но не ранее выданные достижения.
        // Сначала переносим все совпавшие слова, чтобы замена не затронула другой уровень.
        for ((rawKey, progress) in retired) {
            if (progress.achievements.isEmpty()) continue
            val word = Alphabet.normalize(rawKey)
            val number = progress.achievementLevelNumber
                ?: previousCampaign?.levels?.firstOrNull { Alphabet.normalize(it.word) == word }?.number
                ?: historicalLevelWords.entries.firstOrNull { Alphabet.normalize(it.value) == word }?.key
            val replacement = newByWord.values.firstOrNull { it.number == number && it.word !in result }
            val key = replacement?.word ?: word
            // Неизвестное соответствие или удалённый уровень: достижение остаётся
            // в сохранении под прежним ключом, без приписывания чужому уровню.
            if (key !in result) result[key] = CampaignLevelProgress(
                achievements = progress.achievements,
                hintsUsed = progress.hintsUsed || progress.hints.shownSteps > 0 || progress.hints.paidUnits > 0,
                achievementLevelNumber = number,
            )
        }
        return result
    }

    private fun reconcileLevel(
        level: CampaignLevel,
        old: CampaignLevelProgress,
        campaign: Campaign,
        index: WordIndex,
        previousLevel: CampaignLevel?,
        changes: MutableList<ReconciliationChange>,
    ): CampaignLevelProgress {
        val game = GameState.start(level.word, index, old.found)
        val normalizedOld = old.found.map(Alphabet::normalize).filter { it.isNotEmpty() }.distinct()
        if (game.found.size != normalizedOld.size) {
            changes += ReconciliationChange(level.word, ReconciliationChangeKind.FOUND_WORDS_FILTERED,
                "kept=${game.found.size},removed=${normalizedOld.size - game.found.size}")
        }

        val taskById = level.tasks.associateBy { it.id }
        val previousById = previousLevel?.tasks?.associateBy { it.id }.orEmpty()
        val preserved = old.completedTaskIds.filter { id ->
            val current = taskById[id] ?: return@filter false
            if (!idMatchesTaskType(id, current)) {
                changes += ReconciliationChange(level.word, ReconciliationChangeKind.TASK_SEMANTICS_CHANGED,
                    "$id:${current.type}")
                return@filter false
            }
            val previous = previousById[id] ?: return@filter true
            val same = sameSemantics(previous, current)
            if (!same) {
                changes += ReconciliationChange(level.word, ReconciliationChangeKind.TASK_SEMANTICS_CHANGED, id)
            }
            same
        }.toSet()
        val removed = old.completedTaskIds.filter { it !in taskById }
        if (removed.isNotEmpty()) {
            changes += ReconciliationChange(level.word, ReconciliationChangeKind.TASK_REMOVED, removed.sorted().joinToString(","))
        }
        if (preserved.isNotEmpty() && preserved == old.completedTaskIds.intersect(taskById.keys)) {
            changes += ReconciliationChange(level.word, ReconciliationChangeKind.TASK_STARS_PRESERVED, preserved.sorted().joinToString(","))
        }

        val hints = reconcileHints(old.hints, level, game, changes)
        val evaluated = TaskRules.evaluate(
            CurrentCampaignLevel(level, game, preserved, hints),
            campaign.countMinLength,
        ).state
        val fresh = evaluated.completedTaskIds - preserved
        if (fresh.isNotEmpty()) {
            changes += ReconciliationChange(level.word, ReconciliationChangeKind.TASK_COMPLETED_FROM_FOUND,
                fresh.sorted().joinToString(","))
        }
        if (preserved.any { it !in fresh } || fresh.isNotEmpty()) {
            changes += ReconciliationChange(level.word, ReconciliationChangeKind.TASK_STARS_RECOMPUTED,
                "stars=${evaluated.completedTaskIds.size}")
        }
        val riddleTargets = level.tasks.filterIsInstance<CampaignTask.T4>()
            .flatMap { it.targets }.filter { it in evaluated.game.found }.toSet()
        // Старые сохранения не различают потраченный бюджет и сброшенную лестницу,
        // поэтому признак подсказки выводится из любого ненулевого счётчика.
        val hintsUsed = old.hintsUsed || old.hints.shownSteps > 0 || old.hints.paidUnits > 0
        val achievements = AchievementRules.evaluate(
            level = level,
            game = evaluated.game,
            completedTaskIds = evaluated.completedTaskIds,
            hints = evaluated.hints,
            alreadyEarned = old.achievements,
            hintsUsed = hintsUsed,
        )
        val newAchievements = achievements - old.achievements
        if (newAchievements.isNotEmpty()) {
            changes += ReconciliationChange(level.word, ReconciliationChangeKind.ACHIEVEMENTS_GRANTED,
                newAchievements.joinToString(",") { it.name })
        }
        return CampaignLevelProgress(
            found = evaluated.game.found,
            completedTaskIds = evaluated.completedTaskIds,
            hints = evaluated.hints,
            solvedRiddleTargets = riddleTargets,
            achievements = achievements,
            hintsUsed = hintsUsed,
            achievementLevelNumber = level.number.takeIf { achievements.isNotEmpty() },
        )
    }

    /** Покупки и счётчики остаются; сбрасывается только лестница несуществующей цели. */
    private fun reconcileHints(
        hints: HintState,
        level: CampaignLevel,
        game: GameState,
        changes: MutableList<ReconciliationChange>,
    ): HintState {
        val target = hints.ladder.target
        val taskExists = target?.taskId?.let { id -> level.tasks.any { it.id == id } } ?: true
        val wordExists = target?.word?.isNotEmpty() == true && target.word in game.allWords
        val preferredExists = hints.preferredTaskId?.let { id -> level.tasks.any { it.id == id } } ?: true
        if (taskExists && wordExists && preferredExists) return hints
        changes += ReconciliationChange(level.word, ReconciliationChangeKind.HINT_LADDER_RESET, target?.word)
        return hints.copy(ladder = HintLadder(), preferredTaskId = if (preferredExists) hints.preferredTaskId else null)
    }

    /**
     * ID кампании имеют суффикс типа (`l12-t4`). Если тот же ID теперь принадлежит
     * заданию другого типа, звезда не переносится даже без прежней конфигурации.
     */
    private fun idMatchesTaskType(id: String, task: CampaignTask): Boolean {
        val suffix = Regex("-t([1-7])$").find(id) ?: return true
        return suffix.groupValues[1].toInt() == task.type.ordinal + 1
    }

    /** T4/T6 сравниваются как наборы: перестановка целей не делает задание новым. */
    private fun sameSemantics(previous: CampaignTask, current: CampaignTask): Boolean {
        if (previous.type != current.type) return false
        return when {
            previous is CampaignTask.T4 && current is CampaignTask.T4 ->
                previous.id == current.id && previous.targets.toSet() == current.targets.toSet()
            previous is CampaignTask.T6 && current is CampaignTask.T6 ->
                previous.id == current.id && previous.category == current.category &&
                    previous.n == current.n && previous.words.toSet() == current.words.toSet()
            else -> previous == current
        }
    }
}
