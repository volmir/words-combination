package com.example.sostavslova.core

import java.security.MessageDigest

/**
 * Идентичность пользовательских данных кампании. Считается по набору исходных
 * слов уровней, а не по байтам `campaign.json`: правка порогов, заданий, порядка
 * уровней или текстов не меняет идентичность и не сбрасывает прогресс.
 *
 * [Campaign.contentSha256] остаётся контрольной суммой установленного файла для
 * проверки целостности. Идентификатором сохранений он больше не является.
 */
object CampaignIdentity {
    /** Префикс отделяет новую идентичность от исторических SHA-256 файла. */
    const val PREFIX = "words:"

    /**
     * Исторический campaignId установок short15. Константа совместимости:
     * вычислять её из текущего `campaign.json` нельзя, файл уже менялся.
     */
    const val LEGACY_SHORT15_SHA = "1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e"

    /** SHA-256 кампании из README/CAMPAIGN.md на момент перехода; тоже исторический ID. */
    const val LEGACY_SHORT15_RELEASE_SHA = "995e8c8d454dfe1c50a7c68b8c6d3308716263d41183e4bf6b06fd73990451e4"

    /** Известные исторические идентификаторы. Новые сохранения их не используют. */
    val LEGACY_CAMPAIGN_IDS: Set<String> = setOf(LEGACY_SHORT15_SHA, LEGACY_SHORT15_RELEASE_SHA)

    private val hexSha256 = Regex("[0-9a-f]{64}")

    /**
     * Каноническая идентичность: нормализованные слова уровней без учёта порядка,
     * разделённые `\n`, SHA-256 UTF-8 с префиксом [PREFIX].
     */
    fun of(campaign: Campaign): String = ofWords(campaign.levels.map { it.word })

    fun ofWords(words: List<String>): String {
        require(words.isNotEmpty()) { "Campaign must contain levels" }
        val normalized = words.map(Alphabet::normalize)
        require(normalized.all { it.isNotEmpty() }) { "Campaign source words must not be blank" }
        require(normalized.distinct().size == normalized.size) {
            "Campaign source words must be unique after normalization"
        }
        return PREFIX + sha256(normalized.sorted().joinToString("\n"))
    }

    /** Старый campaignId — SHA-256 байтов файла, а не набора слов. */
    fun isLegacyContentSha(campaignId: String?): Boolean = campaignId != null && hexSha256.matches(campaignId)

    /** null — сохранение до появления идентификатора; тоже совместимо. */
    fun isLegacyIdentity(campaignId: String?): Boolean =
        campaignId == null || isLegacyContentSha(campaignId) || campaignId in LEGACY_CAMPAIGN_IDS

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
