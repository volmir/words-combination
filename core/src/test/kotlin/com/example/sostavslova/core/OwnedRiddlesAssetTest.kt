package com.example.sostavslova.core

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnedRiddlesAssetTest {
    @Test fun exactApprovedTextsCoverAllSixTasksAndElevenTargets() {
        val asset = File("../data/owned_riddles.json").readBytes()
        val json = JSONObject(asset.decodeToString())
        assertEquals("owned-riddles", json.getString("schema"))
        assertEquals(1, json.getInt("version"))
        assertEquals("CC0-1.0", json.getString("license"))
        val texts = json.getJSONObject("riddles")
        val campaign = requireNotNull(TestData.campaign)
        val tasks = campaign.levels.flatMap { it.tasks }.filterIsInstance<CampaignTask.T4>()
        assertEquals(6, tasks.size)
        assertEquals(11, texts.length())
        assertEquals(tasks.flatMap { it.targets }.toSet(), texts.keys().asSequence().toSet())
        val entries = JSONObject(File("../docs/owned_hints.json").readText()).getJSONArray("entries")
        val source = (0 until entries.length()).associate { i ->
            entries.getJSONObject(i).let { it.getString("word") to it.getString("hint") }
        }
        for (word in texts.keys()) {
            assertEquals(Alphabet.normalize(word), word)
            val text = texts.getString(word)
            assertTrue(text.isNotBlank())
            assertEquals(source[word], text)
            assertEquals(text, DefinitionHint.prepare(word, text))
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(asset).joinToString("") { "%02x".format(it) }
        assertEquals(JSONObject(File("../data/manifest.json").readText()).getJSONObject("owned_riddles").getString("sha256"), sha)
        val level = campaign.levels.single { it.number == 12 }
        assertEquals("проблематика", level.word)
        assertEquals(8, level.tasks.filterIsInstance<CampaignTask.T3>().single().minLength)
        assertTrue("проблема" in level.tasks.filterIsInstance<CampaignTask.T4>().single().targets)
    }
}
