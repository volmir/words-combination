package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignIdentityTest {
    private val index = WordIndex(listOf("река", "каре", "перо", "пир", "рекуперация", "опровержение"))

    private fun campaign(vararg words: String) = Campaign(
        listOf(Stage(1, 1, words.size)),
        words.mapIndexed { i, word ->
            CampaignLevel(i + 1, word, 1, 1, listOf(CampaignTask.T1("l${i + 1}-t1", 1)))
        },
    )

    @Test fun identityDependsOnWordsNotOnJsonOrOrder() {
        val first = CampaignIdentity.of(campaign("рекуперация", "опровержение"))
        val reordered = CampaignIdentity.of(campaign("опровержение", "рекуперация"))
        assertEquals(first, reordered)
        assertTrue(first.startsWith(CampaignIdentity.PREFIX))
        assertTrue(first.removePrefix(CampaignIdentity.PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun identityChangesWhenAWordChanges() {
        assertNotEquals(
            CampaignIdentity.of(campaign("рекуперация", "опровержение")),
            CampaignIdentity.of(campaign("рекуперация", "хлопкопункт")),
        )
    }

    @Test fun normalizationIsPartOfIdentity() {
        assertEquals(
            CampaignIdentity.ofWords(listOf("Ёлка", "река")),
            CampaignIdentity.ofWords(listOf("елка", "река")),
        )
    }

    @Test fun duplicateWordsAfterNormalizationAreRejected() {
        assertFailsWith<IllegalArgumentException> { CampaignIdentity.ofWords(listOf("ёлка", "елка")) }
    }

    @Test fun legacyConstantsAreStableAndSeparateFromWordIdentity() {
        assertEquals("1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e",
            CampaignIdentity.LEGACY_SHORT15_SHA)
        assertEquals("995e8c8d454dfe1c50a7c68b8c6d3308716263d41183e4bf6b06fd73990451e4",
            CampaignIdentity.LEGACY_SHORT15_RELEASE_SHA)
        assertTrue(CampaignIdentity.isLegacyIdentity(CampaignIdentity.LEGACY_SHORT15_SHA))
        assertTrue(CampaignIdentity.isLegacyIdentity(null))
        assertFalse(CampaignIdentity.isLegacyIdentity("words:${"a".repeat(64)}"))
        assertNotEquals(
            CampaignIdentity.PREFIX + CampaignIdentity.LEGACY_SHORT15_SHA,
            CampaignIdentity.of(campaign("рекуперация", "опровержение")),
        )
        assertEquals(2, CampaignIdentity.LEGACY_CAMPAIGN_IDS.size)
        assertTrue(CampaignIdentity.of(campaign("рекуперация", "опровержение")).isNotEmpty())
        assertTrue(index.contains("река"))
    }
}
