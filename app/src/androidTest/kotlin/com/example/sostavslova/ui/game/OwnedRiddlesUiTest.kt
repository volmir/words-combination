package com.example.sostavslova.ui.game

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import com.example.sostavslova.MainActivity
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.di.AppContainer
import com.example.sostavslova.di.LocalAppContainer
import com.example.sostavslova.ui.WordsApp
import com.example.sostavslova.ui.dismissWelcomeIfShown
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Production assets and navigation on the native Activity; only progress is isolated. */
class OwnedRiddlesUiTest {
    private lateinit var container: AppContainer
    private lateinit var directory: File
    val compose = createAndroidComposeRule<MainActivity>()
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) activity.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { WordsTheme { WordsApp() } }
        }
    }
    private val storage = object : ExternalResource() {
        override fun before() = runTest {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            directory = File(context.cacheDir,"json-ui-${UUID.randomUUID()}").apply { mkdirs() }
            val isolated = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = directory
            }
            val campaign = requireNotNull(LevelsRepository({ error("No legacy") }, readCampaign = {
                context.assets.open("campaign.json").bufferedReader().use { it.readText() }
            }).getCampaign())
            val entries = JSONObject()
            campaign.levels.filter { it.number < 14 }.forEach { level ->
                entries.put(level.word,JSONObject().put("completedTaskIds",JSONArray(level.tasks.map { it.id })))
            }
            val file = File(directory,"seed.preferences_pb")
            val seedScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val seedStore = PreferenceDataStoreFactory.create(scope=seedScope) { file }
            try {
                seedStore.edit { it[stringPreferencesKey("campaignProgressV2")] = JSONObject()
                    .put("version",2).put("campaignId",campaign.contentSha256).put("highestUnlockedLevel",14)
                    .put("levels",entries).toString() }
            } finally { seedScope.cancel();seedScope.coroutineContext.job.join() }
            container=AppContainer(isolated,progressFile={ file })
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
        }
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
            }
            runTest {
                try { container.awaitSaved() } finally {
                    container.applicationScope.cancel();container.applicationScope.coroutineContext.job.join()
                    directory.deleteRecursively()
                }
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(compose)

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    @Test fun allElevenApprovedRiddlesDisplayVerbatimOnSixRealCampaignScreens() = runTest {
        awaitTag("levels_grid")
        compose.dismissWelcomeIfShown()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val texts=JSONObject(context.assets.open("owned_riddles.json").bufferedReader().use { it.readText() }).getJSONObject("riddles")
        val campaign=requireNotNull(container.levelsRepository.getCampaign())
        var checked=0
        for (level in campaign.levels.filter { l -> l.tasks.any { it is CampaignTask.T4 } }) {
            compose.onNodeWithTag("levels_grid").performScrollToNode(hasTestTag("level_card_${level.number}"))
            compose.onNodeWithTag("level_card_${level.number}").performClick()
            awaitTag("game_tasks_summary")
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Новые задания этапа").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Закрыть").performClick()
            }
            for (task in level.tasks.filterIsInstance<CampaignTask.T4>()) for (word in task.targets) {
                compose.onNodeWithText(texts.getString(word)).performScrollTo().assertIsDisplayed()
                    .assert(hasAnyAncestor(hasTestTag("level_task_t4")))
                checked++
            }
            compose.onAllNodes(hasText("Слово из", substring=true)).assertCountEquals(0)
            if (level.number==12) {
                val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
                File(context.cacheDir,"json-riddles-level12.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            awaitTag("levels_grid")
        }
        assertEquals(11,checked)
    }
}
