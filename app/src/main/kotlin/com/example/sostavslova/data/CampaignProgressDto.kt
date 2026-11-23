package com.example.sostavslova.data

import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.HintLadder
import com.example.sostavslova.core.HintStage
import com.example.sostavslova.core.HintState
import com.example.sostavslova.core.HintStep
import com.example.sostavslova.core.HintTarget
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.core.TaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Единственный JSON кампании; :core не зависит от формата хранения и Android. */
@Serializable
internal data class CampaignProgressDto(
    val version: Int = 2,
    val levels: Map<String, CampaignLevelProgressDto> = emptyMap(),
    val highestUnlockedLevel: Int = 1,
    val lastVisitedLevel: Int? = null,
    val seenExplanations: Set<String> = emptySet(),
    val finaleReached: Boolean = false,
    val finaleShown: Boolean = false,
    val campaignId: String? = null,
    val resetReason: String? = null,
) {
    fun toCore(): CampaignProgress {
        if (version != 2) throw SerializationException("Unsupported campaign progress version: $version")
        return CampaignProgress(
            levels = levels.mapValues { it.value.toCore() },
            highestUnlockedLevel = highestUnlockedLevel,
            lastVisitedLevel = lastVisitedLevel,
            seenExplanations = seenExplanations.map { TaskType.valueOf(it) }.toSet(),
            finaleReached = finaleReached,
            finaleShown = finaleShown,
            campaignId = campaignId,
            resetReason = resetReason,
        )
    }

    companion object {
        fun fromCore(state: CampaignProgress) = CampaignProgressDto(
            levels = state.levels.mapValues { CampaignLevelProgressDto.fromCore(it.value) },
            highestUnlockedLevel = state.highestUnlockedLevel,
            lastVisitedLevel = state.lastVisitedLevel,
            seenExplanations = state.seenExplanations.map { it.name }.toSet(),
            finaleReached = state.finaleReached,
            finaleShown = state.finaleShown,
            campaignId = state.campaignId,
            resetReason = state.resetReason,
        )
    }
}

@Serializable
internal data class CampaignLevelProgressDto(
    val found: List<String> = emptyList(),
    val completedTaskIds: Set<String> = emptySet(),
    val solvedRiddleTargets: Set<String> = emptySet(),
    val hints: HintStateDto = HintStateDto(),
    /** Имена достижений: неизвестные будущие значения молча игнорируются. */
    val achievements: Set<String> = emptySet(),
    /** null — старое сохранение без поля; признак выводится из счётчиков подсказок. */
    val hintsUsed: Boolean? = null,
    val achievementLevelNumber: Int? = null,
) {
    fun toCore(): CampaignLevelProgress {
        val restoredHints = hints.toCore()
        return CampaignLevelProgress(
            found = found,
            completedTaskIds = completedTaskIds,
            hints = restoredHints,
            solvedRiddleTargets = solvedRiddleTargets,
            achievements = achievements.mapNotNull { name ->
                enumValues<LevelAchievement>().firstOrNull { it.name == name }
            }.toSet(),
            hintsUsed = hintsUsed ?: (restoredHints.shownSteps > 0 || restoredHints.paidUnits > 0),
            achievementLevelNumber = achievementLevelNumber,
        )
    }

    companion object {
        fun fromCore(state: CampaignLevelProgress) = CampaignLevelProgressDto(
            found = state.found,
            completedTaskIds = state.completedTaskIds,
            solvedRiddleTargets = state.solvedRiddleTargets,
            hints = HintStateDto.fromCore(state.hints),
            achievements = state.achievements.map { it.name }.toSet(),
            hintsUsed = state.hintsUsed,
            achievementLevelNumber = state.achievementLevelNumber,
        )
    }
}

@Serializable
internal data class HintStateDto(
    val target: HintTargetDto? = null,
    val steps: List<HintStepDto> = emptyList(),
    val nextStepIndex: Int = 0,
    val revealedPositions: Set<Int> = emptySet(),
    val shownSteps: Int = 0,
    val paidUnits: Int = 0,
    val preferredTaskId: String? = null,
    val ownedContentVersion: Int = 0,
    val letterLadderVersion: Int = 0,
    val paidSteps: Int = 0,
) {
    fun toCore() = HintState(
        HintLadder(target?.toCore(), steps.map { it.toCore() }, nextStepIndex, revealedPositions, letterLadderVersion, paidSteps),
        shownSteps, paidUnits, preferredTaskId,
    ).let { if (ownedContentVersion < 1) it.withoutLegacyDefinitions() else it }

    companion object {
        fun fromCore(state: HintState) = HintStateDto(
            state.ladder.target?.let(HintTargetDto::fromCore),
            state.ladder.steps.map(HintStepDto::fromCore), state.ladder.nextStepIndex,
            state.ladder.revealedPositions, state.shownSteps, state.paidUnits, preferredTaskId = state.preferredTaskId,
            ownedContentVersion = 1, letterLadderVersion = state.ladder.letterLadderVersion,
            paidSteps = state.ladder.paidSteps,
        )
    }
}

@Serializable
internal data class HintTargetDto(
    val word: String = "",
    val taskId: String? = null,
    val taskType: String? = null,
    val letter: Char? = null,
) {
    fun toCore() = HintTarget(word, taskId, taskType?.let(TaskType::valueOf), letter)

    companion object {
        fun fromCore(target: HintTarget) = HintTargetDto(target.word, target.taskId, target.taskType?.name, target.letter)
    }
}

@Serializable
internal data class HintStepDto(
    val stage: String = "LENGTH",
    val text: String? = null,
    val length: Int? = null,
    val revealedPositions: Set<Int> = emptySet(),
    val wiktionary: Boolean = false,
) {
    fun toCore() = HintStep(HintStage.valueOf(stage), text, length, revealedPositions, wiktionary)

    companion object {
        fun fromCore(step: HintStep) = HintStepDto(step.stage.name, step.text, step.length, step.revealedPositions, step.wiktionary)
    }
}
