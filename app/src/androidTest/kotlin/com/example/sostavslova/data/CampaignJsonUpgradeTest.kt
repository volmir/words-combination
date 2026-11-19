package com.example.sostavslova.data

import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.edit
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignRules

import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.HintStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CampaignJsonUpgradeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun installedCampaignV2KeepsIdentityFrontierStarsWordsAndPurchasedLadderAcrossNewStore() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        val levels = LevelsRepository(readCampaign = { asset("campaign.json") })
        val campaign = requireNotNull(levels.getCampaign())
        val words = WordRepository({ asset("words.txt") }).requireIndex()
        assertEquals(BuildConfig.CAMPAIGN_SHA256, campaign.contentSha256)
        val entries = JSONObject()
        campaign.levels.filter { it.number < 12 }.forEach { level ->
            entries.put(level.word, JSONObject().put("completedTaskIds", JSONArray(level.tasks.map { it.id })))
        }
        val target = campaign.levels.single { it.number == 12 }
        val task = target.tasks.filterIsInstance<CampaignTask.T4>().single()
        val json = JSONObject("""{"found":["билет"],"completedTaskIds":["l12-t3"],"hints":{
            "ownedContentVersion":1,"letterLadderVersion":1,"shownSteps":7,"paidUnits":2,
            "target":{"word":"проблема","taskId":"${task.id}","taskType":"T4"},
            "steps":[{"stage":"FIRST_LETTER","text":"п______","revealedPositions":[0]},
            {"stage":"PATTERN","text":"п_о____","revealedPositions":[0,2]},
            {"stage":"ANSWER","text":"проблема","revealedPositions":[0,1,2,3,4,5,6]}],
            "nextStepIndex":2,"revealedPositions":[0,2]}}""")
        entries.put(target.word, json)
        val seed = JSONObject().put("version",2).put("campaignId",CampaignIdentity.of(campaign))
            .put("highestUnlockedLevel",12).put("levels",entries).toString()
        val file = temporary.newFolder().resolve("upgrade.json")
        val key = stringPreferencesKey("campaignProgressV2")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.edit { it[key] = seed }
            var repository = ProgressRepository(store)
            val before = repository.observeCampaignProgress().first()
            val upgraded = repository.migrateCampaign(campaign, words)
            assertEquals(before, upgraded)
            assertNull(upgraded.resetReason)
            assertEquals(12, CampaignRules.frontier(campaign, upgraded))
            val current = requireNotNull(CampaignRules.openLevel(campaign, upgraded,12,words))
            assertEquals(listOf("билет"), current.game.found)
            assertEquals(listOf("п______","п_о____"),current.hints.ladder.shown.map { it.text })
            assertEquals(2,current.hints.paidUnits)
            assertEquals(1,HintRules.nextCost(current))
            repeat(2) { assertEquals(current, CampaignRules.openLevel(campaign, upgraded,12,words)) }
            assertEquals(seed,store.data.first()[key])
            scope.cancel();scope.coroutineContext.job.join()
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            store=PreferenceDataStoreFactory.create(scope=scope) { file }
            repository=ProgressRepository(store)
            assertEquals(upgraded, repository.migrateCampaign(campaign,words))
            assertNull(repository.requestCampaignHint(campaign,12,6,words))
            assertEquals(upgraded,repository.observeCampaignProgress().first())
            val paid=requireNotNull(repository.requestCampaignHint(campaign,12,7,words))
            assertEquals(HintStage.ANSWER,paid.step?.stage)
            assertEquals(3,paid.state.hints.paidUnits)
            assertNull(repository.requestCampaignHint(campaign,12,7,words))
            val after=repository.observeCampaignProgress().first()
            assertEquals(upgraded.levels.mapValues { it.value.found },after.levels.mapValues { it.value.found })
            assertEquals(upgraded.levels.mapValues { it.value.completedTaskIds },after.levels.mapValues { it.value.completedTaskIds })
            assertEquals(upgraded.campaignId,after.campaignId)
            assertNull(after.resetReason)
        } finally { scope.cancel();scope.coroutineContext.job.join() }
    }
}
