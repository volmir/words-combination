package com.example.sostavslova.data

import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.LevelAchievement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** совместимость JSON: неизвестные имена и отсутствующие поля не ломают чтение. */
class CampaignProgressDtoTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun core() = CampaignProgress(levels = mapOf(
        "первый" to CampaignLevelProgress(
            found = listOf("кот"),
            achievements = setOf(LevelAchievement.EXPERT, LevelAchievement.COLLECTOR,
                LevelAchievement.LONG_WORD),
            hintsUsed = true,
            achievementLevelNumber = 1,
        ),
        "второй" to CampaignLevelProgress(achievements = setOf(LevelAchievement.EXPERT), achievementLevelNumber = 2),
    ))

    @Test fun roundTripKeepsAchievementsSeparatePerLevel() {
        val decoded = json.decodeFromString<CampaignProgressDto>(json.encodeToString(CampaignProgressDto.fromCore(core()))).toCore()
        assertEquals(setOf(LevelAchievement.EXPERT, LevelAchievement.COLLECTOR, LevelAchievement.LONG_WORD),
            decoded.levels.getValue("первый").achievements)
        assertEquals(setOf(LevelAchievement.EXPERT), decoded.levels.getValue("второй").achievements)
        assertTrue(decoded.levels.getValue("первый").hintsUsed)
        assertFalse(decoded.levels.getValue("второй").hintsUsed)
        assertEquals(core(), decoded)
    }

    @Test fun unknownAchievementNamesAreIgnoredWithoutFailingRead() {
        val raw = """{"version":2,"levels":{"уровень":{"achievements":["EXPERT","FUTURE_ACHIEVEMENT"],
            "hints":{"shownSteps":0,"paidUnits":0}}}}"""
        val decoded = json.decodeFromString<CampaignProgressDto>(raw).toCore()
        assertEquals(setOf(LevelAchievement.EXPERT), decoded.levels.getValue("уровень").achievements)
        assertFalse(decoded.levels.getValue("уровень").hintsUsed)
    }

    @Test fun oldSaveWithoutNewFieldsReadsHintsUsedFromCounters() {
        val raw = """{"version":2,"levels":{"уровень":{"found":["кот"],
            "hints":{"shownSteps":2,"paidUnits":0}}}}"""
        val spent = json.decodeFromString<CampaignProgressDto>(raw).toCore().levels.getValue("уровень")
        assertTrue(spent.hintsUsed)
        assertTrue(spent.achievements.isEmpty())

        val ladderReset = """{"version":2,"levels":{"уровень":{
            "hints":{"shownSteps":0,"paidUnits":3}}}}"""
        assertTrue(json.decodeFromString<CampaignProgressDto>(ladderReset).toCore()
            .levels.getValue("уровень").hintsUsed)

        val untouched = """{"version":2,"levels":{"уровень":{
            "hints":{"shownSteps":0,"paidUnits":0}}}}"""
        assertFalse(json.decodeFromString<CampaignProgressDto>(untouched).toCore()
            .levels.getValue("уровень").hintsUsed)
    }
}
