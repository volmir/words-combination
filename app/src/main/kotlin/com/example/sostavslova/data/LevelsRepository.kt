package com.example.sostavslova.data

import com.example.sostavslova.core.Campaign
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class LevelsRepository(
    private val readCampaign: suspend () -> String?,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val expectedCampaignSha256: String? = null,
) {
    private val mutex = Mutex()
    private var campaignLoaded = false
    private var cachedCampaign: Campaign? = null

    /** null означает, что кампания не поставляется; ошибки чтения и валидации пробрасываются. */
    suspend fun getCampaign(): Campaign? = mutex.withLock {
        if (campaignLoaded) return@withLock cachedCampaign
        val text = withContext(ioDispatcher) { readCampaign() }
        val campaign = withContext(computationDispatcher) {
            text?.let {
                val identity = sha256(it)
                require(expectedCampaignSha256 == null || expectedCampaignSha256 == identity) {
                    "Campaign asset hash does not match BuildConfig.CAMPAIGN_SHA256"
                }
                CampaignJson.decode(it).copy(contentSha256 = identity)
            }
        }
        cachedCampaign = campaign
        campaignLoaded = true
        campaign
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
