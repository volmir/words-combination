package com.example.sostavslova.data

import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.HintLadder
import com.example.sostavslova.core.HintStage
import com.example.sostavslova.core.HintState
import com.example.sostavslova.core.HintStep
import com.example.sostavslova.core.HintTarget
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
        )
    }
}

@Serializable
internal data class CampaignLevelProgressDto(
    val found: List<String> = emptyList(),
    val completedTaskIds: Set<String> = emptySet(),
    val solvedRiddleTargets: Set<String> = emptySet(),
    val hints: HintStateDto = HintStateDto(),
) {
    fun toCore() = CampaignLevelProgress(found, completedTaskIds, hints.toCore(), solvedRiddleTargets)

    companion object {
        fun fromCore(state: CampaignLevelProgress) = CampaignLevelProgressDto(
            state.found, state.completedTaskIds, state.solvedRiddleTargets, HintStateDto.fromCore(state.hints),
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
) {
    fun toCore() = HintState(
        HintLadder(target?.toCore(), steps.map { it.toCore() }, nextStepIndex, revealedPositions),
        shownSteps, paidUnits,
    )

    companion object {
        fun fromCore(state: HintState) = HintStateDto(
            state.ladder.target?.let(HintTargetDto::fromCore),
            state.ladder.steps.map(HintStepDto::fromCore), state.ladder.nextStepIndex,
            state.ladder.revealedPositions, state.shownSteps, state.paidUnits,
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
