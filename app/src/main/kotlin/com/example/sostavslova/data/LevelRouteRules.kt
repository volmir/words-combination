package com.example.sostavslova.data

import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules

/** Номер маршрута начинается с 1. Одна проверка для карты и прямого входа. */
object LevelRouteRules {
    fun exists(number: Int?, campaign: Campaign): Boolean =
        number != null && number > 0 && number in 1..campaign.levels.size

    fun isUnlocked(number: Int?, campaign: Campaign, saved: CampaignProgress): Boolean {
        if (!exists(number, campaign)) return false
        return CampaignRules.isUnlocked(campaign, saved, requireNotNull(number))
    }
}
