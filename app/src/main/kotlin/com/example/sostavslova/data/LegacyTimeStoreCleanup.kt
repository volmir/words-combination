package com.example.sostavslova.data

import java.io.File

/**
 * Best-effort удаление файлов прежнего локального хранилища времени.
 * Ничего не создаёт и не пишет: после вызова приложение не возобновляет учёт.
 */
object LegacyTimeStoreCleanup {
    private const val STORE_FILE = "play_time.json"
    private const val LEGACY_STORE_FILE = "play_time.preferences_pb"

    fun delete(directory: File) {
        listOf(STORE_FILE, "$STORE_FILE.tmp", LEGACY_STORE_FILE, "$LEGACY_STORE_FILE.tmp")
            .forEach { name -> runCatching { File(directory, name).takeIf(File::exists)?.delete() } }
    }
}
