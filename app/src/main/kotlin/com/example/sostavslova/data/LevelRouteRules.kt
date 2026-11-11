package com.example.sostavslova.data

import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.LevelRules

/** Номер маршрута начинается с 1. Одна проверка для карты и прямого входа. */
object LevelRouteRules {
    fun exists(number: Int?, campaign: Campaign?, legacy: List<Level>): Boolean =
        number != null && number > 0 && if (campaign != null) number in 1..campaign.levels.size
        else legacy.any { it.index == number }

    fun isUnlocked(number: Int?, campaign: Campaign?, saved: CampaignProgress,
        legacy: List<Level>, stars: Map<String, LevelProgress>): Boolean {
        if (!exists(number, campaign, legacy)) return false
        val valid = requireNotNull(number)
        return if (campaign != null) CampaignRules.isUnlocked(campaign, saved, valid)
        else LevelRules.isUnlocked(legacy, stars, legacy.indexOfFirst { it.index == valid })
    }
}
