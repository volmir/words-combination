package com.example.sostavslova.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.core.Alphabet
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Не создаёт пустую БД при отсутствии assets; SQL и копирование выполняются только на IO. */
class SqliteDefinitionsRepository(
    context: Context,
    private val hasDefinitions: Boolean = BuildConfig.HAS_DEFINITIONS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    assetOpener: (() -> InputStream)? = null,
    private val databaseName: String = "definitions.db",
) : DefinitionsRepository {
    private val appContext = context.applicationContext
    private val openAsset = assetOpener ?: { appContext.assets.open("definitions.db") }
    private val mutex = Mutex()
    private var initialized = false
    private var helper: DefinitionsOpenHelper? = null

    override suspend fun isAvailable(): Boolean = withContext(ioDispatcher) {
        mutex.withLock { prepareDatabase() != null }
    }

    override suspend fun definitionsFor(word: String): List<Definition> = withContext(ioDispatcher) {
        val normalized = Alphabet.normalize(word)
        if (normalized.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            val database = prepareDatabase() ?: return@withLock emptyList()
            try {
                // Поддерживаем прежние базы без source; новые сборки имеют user_version >= 2.
                val fields = database.rawQuery("PRAGMA table_info(definitions)", null).use { columns ->
                    val nameIndex = columns.getColumnIndexOrThrow("name")
                    buildSet {
                        while (columns.moveToNext()) add(columns.getString(nameIndex))
                    }
                }
                val projection = mutableListOf("display", "text", "notes", "antonym", "example")
                if ("source" in fields) projection.add("source")
                if ("origin" in fields) projection.add("origin")
                val (forms, related) = wordMetadata(database, normalized)
                database.query(
                    "definitions", projection.toTypedArray(),
                    "word = ?", arrayOf(normalized), null, null, "ord ASC, id ASC",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(Definition(
                                display = cursor.getString(0).orEmpty(),
                                text = cursor.getString(1).orEmpty(),
                                notes = cursor.getString(2).orEmpty(),
                                antonym = cursor.getString(3).orEmpty(),
                                example = cursor.getString(4).orEmpty(),
                                source = if ("source" in fields) cursor.getString(cursor.getColumnIndexOrThrow("source")).orEmpty() else "",
                                origin = if ("origin" in fields) cursor.getString(cursor.getColumnIndexOrThrow("origin")).orEmpty() else "",
                                forms = forms, related = related,
                            ))
                        }
                    }
                }
            } catch (_: SQLiteException) {
                helper?.close()
                helper = null
                emptyList()
            }
        }
    }

    override suspend fun categoryFor(word: String): String? = withContext(ioDispatcher) {
        val normalized = Alphabet.normalize(word)
        if (normalized.isEmpty()) return@withContext null
        mutex.withLock {
            val database = prepareDatabase() ?: return@withLock null
            try {
                val fields = database.rawQuery("PRAGMA table_info(word_metadata)", null).use { columns ->
                    buildSet { while (columns.moveToNext()) add(columns.getString(columns.getColumnIndexOrThrow("name"))) }
                }
                if ("category" !in fields) return@withLock null
                database.query("word_metadata", arrayOf("category"), "word = ?", arrayOf(normalized),
                    null, null, null).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0)?.takeIf {
                        it != "other" && CategoryNames.resource(it) != null
                    }
                }
            } catch (_: SQLiteException) { null }
        }
    }

    private fun wordMetadata(database: SQLiteDatabase, word: String): Pair<List<String>, List<String>> {
        val fields = database.rawQuery("PRAGMA table_info(word_metadata)", null).use { columns ->
            val nameIndex = columns.getColumnIndex("name")
            buildSet { if (nameIndex >= 0) while (columns.moveToNext()) add(columns.getString(nameIndex)) }
        }
        if (!fields.containsAll(listOf("forms", "related"))) return emptyList<String>() to emptyList()
        return database.query("word_metadata", arrayOf("forms", "related"), "word = ?", arrayOf(word),
            null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) emptyList<String>() to emptyList()
            else jsonWords(cursor.getString(0)) to jsonWords(cursor.getString(1))
        }
    }

    private fun jsonWords(value: String?): List<String> = try {
        val array = JSONArray(value ?: "[]")
        (0 until array.length()).map { array.getString(it) }
    } catch (_: JSONException) { emptyList() }

    private fun prepareDatabase(): SQLiteDatabase? {
        if (initialized) return helper?.readableDatabase
        initialized = true
        if (!hasDefinitions) {
            // Не оставляем на устройстве определения из прежней непроверенной сборки.
            appContext.deleteDatabase(databaseName)
            return null
        }
        var staged: File? = null
        var opened: DefinitionsOpenHelper? = null
        try {
            val stagedFile = File.createTempFile("definitions-", ".db", appContext.cacheDir)
            staged = stagedFile
            openAsset().use { source -> stagedFile.outputStream().use { source.copyTo(it) } }
            val assetVersion = readVersion(stagedFile)
            if (assetVersion <= 0) throw SQLiteException("Некорректный PRAGMA user_version базы определений")
            val destination = appContext.getDatabasePath(databaseName)
            val installedVersion = if (destination.isFile) {
                try { readVersion(destination) } catch (_: SQLiteException) { -1 }
            } else {
                -1
            }
            if (installedVersion < assetVersion) {
                destination.parentFile?.mkdirs()
                // Наша БД только для чтения; убираем остатки журналов старой версии.
                listOf("-wal", "-shm", "-journal").forEach { File(destination.path + it).delete() }
                val atomicFile = AtomicFile(destination)
                val output = atomicFile.startWrite()
                try {
                    stagedFile.inputStream().use { it.copyTo(output) }
                    atomicFile.finishWrite(output)
                } catch (error: Exception) {
                    atomicFile.failWrite(output)
                    throw error
                }
            }
            opened = DefinitionsOpenHelper(appContext, databaseName, maxOf(installedVersion, assetVersion))
            val database = opened.readableDatabase
            helper = opened
            return database
        } catch (_: IOException) {
            opened?.close()
            return null
        } catch (_: SQLiteException) {
            opened?.close()
            return null
        } finally {
            staged?.delete()
        }
    }

    private fun readVersion(file: File): Int = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    ).use { it.version }

    suspend fun close() = withContext(ioDispatcher) {
        mutex.withLock {
            helper?.close()
            helper = null
            initialized = false
        }
    }

    private class DefinitionsOpenHelper(context: Context, name: String, version: Int) :
        SQLiteOpenHelper(context, name, null, version) {
        override fun onCreate(db: SQLiteDatabase) {
            throw SQLiteException("Ожидалась предварительно скопированная база определений")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Обновление базы определений выполняется копированием assets")
        }

        override fun onOpen(db: SQLiteDatabase) {
            db.execSQL("PRAGMA query_only = ON")
        }
    }
}
