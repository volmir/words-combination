package com.example.sostavslova.data

import com.example.sostavslova.data.jsonstore.LegacyPreferencesMigration
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.data.jsonstore.longPreferencesKey
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/** Binary fixtures follow AndroidX PreferenceMap's published schema, not a generated protobuf class. */
class JsonPreferencesMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun oldCampaignAndPlayTimeSurviveOneTimeMigrationWithoutRewritingSource() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val legacy = File(directory, "progress.preferences_pb")
        val output = File(directory, "progress.json")
        val progress = """{"version":2,"paidUnits":4,"levels":{"картотека":{"found":["карта"]}}}"""
        val bytes = message(
            entry("campaignProgressV2", wireString(progress)),
            entry("welcomeStateV1", wireString("DONE")),
            entry("synthetic_play_time", wireLong(1234L)),
        )
        legacy.writeBytes(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope, listOf(LegacyPreferencesMigration(legacy, true))) { output }
            val snapshot = store.data.first()
            assertEquals(progress, snapshot[stringPreferencesKey("campaignProgressV2")])
            assertEquals("DONE", snapshot[stringPreferencesKey("welcomeStateV1")])
            assertEquals(1234L, snapshot[longPreferencesKey("synthetic_play_time")])
            assertArrayEquals(bytes, legacy.readBytes())
            assertTrue(output.exists())
            assertEquals(snapshot, PreferencesSerializer.readFrom(output.inputStream()))
        } finally {
            scope.cancel()
        }

        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restarted = PreferenceDataStoreFactory.create(restartedScope) { output }
            assertEquals(progress, restarted.data.first()[stringPreferencesKey("campaignProgressV2")])
            assertArrayEquals(bytes, legacy.readBytes())
        } finally {
            restartedScope.cancel()
        }
    }

    @Test fun malformedLegacyFileNeverSilentlyCreatesEmptyProgress() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val legacy = File(directory, "progress.preferences_pb").apply { writeBytes(byteArrayOf(10, 4, 1)) }
        val output = File(directory, "progress.json")
        val before = legacy.readBytes()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope, listOf(LegacyPreferencesMigration(legacy, true))) { output }
            assertTrue(runCatching { store.data.first() }.isFailure)
            assertArrayEquals(before, legacy.readBytes())
            assertFalse(output.exists())
        } finally {
            scope.cancel()
        }
    }

    private fun wireString(value: String): ByteArray = message(field(5, value.toByteArray(Charsets.UTF_8)))
    private fun wireLong(value: Long): ByteArray = message(varintField(4, value))

    private fun entry(name: String, value: ByteArray): ByteArray =
        field(1, message(field(1, name.toByteArray(Charsets.UTF_8)), field(2, value)))

    private fun field(number: Int, payload: ByteArray): ByteArray =
        message(varint((number shl 3 or 2).toLong()), varint(payload.size.toLong()), payload)

    private fun varintField(number: Int, value: Long): ByteArray =
        message(varint((number shl 3).toLong()), varint(value))

    private fun varint(number: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var value = number
        while (value and -128L != 0L) {
            out.write(((value and 127L) or 128L).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
        return out.toByteArray()
    }

    private fun message(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        parts.forEach { write(it) }
    }.toByteArray()
}
