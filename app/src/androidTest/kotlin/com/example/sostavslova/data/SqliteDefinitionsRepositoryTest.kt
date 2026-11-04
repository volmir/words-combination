package com.example.sostavslova.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteDefinitionsRepositoryTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var databaseName: String
    private val repositories = mutableListOf<SqliteDefinitionsRepository>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        directory = File(context.cacheDir, "definitions-test-$id").apply { mkdirs() }
        databaseName = "definitions-test-$id.db"
    }

    @After
    fun tearDown() = runTest {
        repositories.forEach { it.close() }
        withContext(Dispatchers.IO) {
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }

    private fun repository(
        asset: File? = null,
        included: Boolean = true,
        reads: AtomicInteger = AtomicInteger(),
    ) = SqliteDefinitionsRepository(
        context, hasDefinitions = included, databaseName = databaseName,
        assetOpener = {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            reads.incrementAndGet()
            asset?.inputStream() ?: throw FileNotFoundException("No test defs.db")
        },
    ).also { repositories.add(it) }

    private fun createDatabase(file: File, version: Int, text: String, word: String = "портал", source: String? = null) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE definitions(id INTEGER PRIMARY KEY, word TEXT, display TEXT, ord INTEGER, text TEXT, notes TEXT, antonym TEXT, example TEXT)")
            if (source != null) db.execSQL("ALTER TABLE definitions ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX definitions_word ON definitions(word)")
            val row = ContentValues().apply {
                put("word", word); put("display", word); put("ord", 1); put("text", text)
                if (source != null) put("source", source)
            }
            db.insertOrThrow("definitions", null, row)
            db.version = version
        }
    }

    @Test
    fun absentDefinitionsReturnFalseAndEmptyListWithoutCreatingDatabase() = runTest {
        val reads = AtomicInteger()
        val repository = repository(included = false, reads = reads)
        assertFalse(withContext(Dispatchers.Main) { repository.isAvailable() })
        assertEquals(emptyList<Definition>(), repository.definitionsFor("портал"))
        assertEquals(0, reads.get())
        assertFalse(context.getDatabasePath(databaseName).exists())
    }

    @Test
    fun missingAssetIsAlsoSafeWhenBuildFlagIsTrue() = runTest {
        val repository = repository()
        assertFalse(repository.isAvailable())
        assertTrue(repository.definitionsFor("портал").isEmpty())
        assertFalse(context.getDatabasePath(databaseName).exists())
    }

    @Test
    fun normalizedQueriesPreservePlainTextAndCopyAssetOnceOffMain() = runTest {
        val asset = File(directory, "asset.db")
        val text = "<органы управления>\nВторая строка"
        createDatabase(asset, 1, text, "еж")
        val reads = AtomicInteger()
        val repository = repository(asset, reads = reads)
        val definitions = withContext(Dispatchers.Main) { repository.definitionsFor(" ЁЖ ") }
        assertEquals(listOf(Definition("еж", text)), definitions)
        assertEquals(definitions, repository.definitionsFor("еж"))
        assertTrue(repository.isAvailable())
        assertEquals(1, reads.get())
        assertTrue(repository.definitionsFor("' OR 1=1 --").isEmpty())
    }

    @Test
    fun newerBundledUserVersionReplacesInstalledDatabase() = runTest {
        val oldAsset = File(directory, "old.db")
        createDatabase(oldAsset, 1, "старое значение")
        val first = repository(oldAsset)
        assertEquals("старое значение", first.definitionsFor("портал").single().text)
        first.close()
        val newAsset = File(directory, "new.db")
        val source = "https://ru.wiktionary.org/wiki/портал"
        createDatabase(newAsset, 2, "новое значение", source = source)
        val second = repository(newAsset)
        assertEquals("новое значение", second.definitionsFor("портал").single().text)
        assertEquals(source, second.definitionsFor("портал").single().source)
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals(2, it.version) }
    }

    @Test
    fun olderBundledUserVersionDoesNotDowngradeInstalledDatabase() = runTest {
        createDatabase(context.getDatabasePath(databaseName), 2, "установленная версия")
        val asset = File(directory, "asset.db")
        createDatabase(asset, 1, "старая версия assets")
        assertEquals("установленная версия", repository(asset).definitionsFor("портал").single().text)
    }

    @Test
    fun bundleWithoutDefinitionsDoesNotExposeOldInstalledCopy() = runTest {
        createDatabase(context.getDatabasePath(databaseName), 1, "предыдущая сборка")
        val repository = repository(included = false)
        assertFalse(repository.isAvailable())
        assertTrue(repository.definitionsFor("портал").isEmpty())
        assertFalse(context.getDatabasePath(databaseName).exists())
    }

    @Test
    fun sourceIsReadFromNewSchemaWithoutLosingOtherFields() = runTest {
        val asset = File(directory, "open.db")
        val source = "https://ru.wiktionary.org/wiki/%D1%91%D0%B6"
        createDatabase(asset, 2, "<тест>\nСтрока", "еж", source)
        assertEquals(Definition("еж", "<тест>\nСтрока", source = source),
            withContext(Dispatchers.Main) { repository(asset).definitionsFor("ЁЖ").single() })
    }

    @Test
    fun bundledDictionaryUsesOpenSourceOrRemainsOptional() = runTest {
        val repository = SqliteDefinitionsRepository(context, databaseName = databaseName).also { repositories.add(it) }
        if (com.example.sostavslova.BuildConfig.HAS_DEFINITIONS) {
            assertTrue(repository.isAvailable())
            val definitions = repository.definitionsFor("администрация")
            assertTrue(definitions.isNotEmpty())
            assertTrue(definitions.all { it.source.startsWith("https://ru.wiktionary.org/wiki/") && it.example.isEmpty() })
        } else {
            assertFalse(repository.isAvailable())
            assertTrue(repository.definitionsFor("администрация").isEmpty())
        }
    }
}
