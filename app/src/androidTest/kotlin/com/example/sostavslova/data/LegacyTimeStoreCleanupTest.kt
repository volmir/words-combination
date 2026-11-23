package com.example.sostavslova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyTimeStoreCleanupTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun removesOldTimeStoreWithDataStoreTempsAndKeepsProgress() {
        val directory = temporaryFolder.newFolder()
        val store = File(directory, "play_time.json").apply { writeText("{}") }
        val storeTemp = File(directory, "play_time.json.tmp").apply { writeText("partial") }
        val legacyStore = File(directory, "play_time.preferences_pb").apply { writeBytes(byteArrayOf(1, 2)) }
        val legacyTemp = File(directory, "play_time.preferences_pb.tmp").apply { writeBytes(byteArrayOf(3)) }
        val progress = File(directory, "progress.json").apply { writeText("{}") }

        LegacyTimeStoreCleanup.delete(directory)

        assertFalse(store.exists())
        assertFalse(storeTemp.exists())
        assertFalse(legacyStore.exists())
        assertFalse(legacyTemp.exists())
        assertTrue(progress.exists())
    }

    @Test fun failedRemovalDoesNotStopCleanupAndMissingDirectoryIsSafe() {
        val directory = temporaryFolder.newFolder()
        val blocked = File(directory, "play_time.json").apply {
            mkdirs()
            File(this, "child").writeText("x")
        }
        val storeTemp = File(directory, "play_time.json.tmp").apply { writeText("partial") }

        LegacyTimeStoreCleanup.delete(directory)

        assertTrue(blocked.exists())
        assertFalse(storeTemp.exists())
        LegacyTimeStoreCleanup.delete(File(directory, "absent"))
    }
}
