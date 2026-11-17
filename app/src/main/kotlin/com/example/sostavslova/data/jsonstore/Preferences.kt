package com.example.sostavslova.data.jsonstore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.BufferedSink
import okio.BufferedSource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Immutable, typed view of the small key/value store used by the game. */
open class Preferences internal constructor(
    private val values: Map<Key<*>, Any>
) {
    data class Key<T>(val name: String, internal val kind: String)

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: Key<T>): T? = values[key] as T?

    fun asMap(): Map<Key<*>, Any> = values.toMap()

    override fun equals(other: Any?): Boolean = other is Preferences && values == other.values
    override fun hashCode(): Int = values.hashCode()
}

class MutablePreferences internal constructor(
    private val mutableValues: MutableMap<Preferences.Key<*>, Any>
) : Preferences(mutableValues) {

    operator fun <T : Any> set(
        key: Preferences.Key<T>,
        value: T
    ) {
        mutableValues[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> remove(key: Preferences.Key<T>): T? =
        mutableValues.remove(key) as T?

    fun clear() {
        mutableValues.clear()
    }

    internal fun freeze(): Preferences =
        Preferences(mutableValues.toMap())
}

fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key(name, "string")
fun longPreferencesKey(name: String): Preferences.Key<Long> = Preferences.Key(name, "long")
fun intPreferencesKey(name: String): Preferences.Key<Int> = Preferences.Key(name, "int")
fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = Preferences.Key(name, "boolean")
fun floatPreferencesKey(name: String): Preferences.Key<Float> = Preferences.Key(name, "float")
fun doublePreferencesKey(name: String): Preferences.Key<Double> = Preferences.Key(name, "double")
fun stringSetPreferencesKey(name: String): Preferences.Key<Set<String>> = Preferences.Key(name, "stringSet")

fun emptyPreferences(): Preferences = Preferences(emptyMap())

/** Preserve edit's atomic read/modify/write and the committed snapshot return value. */
suspend fun DataStore<Preferences>.edit(block: suspend (MutablePreferences) -> Unit): Preferences =
    updateData { old ->
        val mutable = MutablePreferences(old.asMap().toMutableMap())
        block(mutable)
        mutable.freeze()
    }

/** Versioned, deterministic JSON. Malformed data must never be treated as a new installation. */
object PreferencesSerializer : Serializer<Preferences>, OkioSerializer<Preferences> {
    override val defaultValue: Preferences = emptyPreferences()
    private val json = Json

    override suspend fun readFrom(input: InputStream): Preferences = decode(input.readBytes())
    override suspend fun writeTo(t: Preferences, output: OutputStream) = output.write(encode(t))
    override suspend fun readFrom(source: BufferedSource): Preferences = decode(source.readByteArray())
    override suspend fun writeTo(t: Preferences, sink: BufferedSink) { sink.write(encode(t)) }

    internal fun encode(preferences: Preferences): ByteArray {
        val entries = preferences.asMap().entries.sortedWith(compareBy({ it.key.name }, { it.key.kind }))
        check(entries.map { it.key.name }.distinct().size == entries.size) { "Duplicate JSON preference key" }
        val serialized = entries.associate { (key, value) ->
            val encodedValue = when (key.kind) {
                "string" -> JsonPrimitive(value as String)
                "long" -> JsonPrimitive(value as Long)
                "int" -> JsonPrimitive(value as Int)
                "boolean" -> JsonPrimitive(value as Boolean)
                "float" -> JsonPrimitive(value as Float)
                "double" -> JsonPrimitive(value as Double)
                "stringSet" -> JsonArray((value as Set<*>).map { JsonPrimitive(it as String) }.sortedBy { it.content })
                else -> error("Unsupported preference kind: ${key.kind}")
            }
            key.name to JsonObject(mapOf("type" to JsonPrimitive(key.kind), "value" to encodedValue))
        }
        return (json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
            "schema" to JsonPrimitive(1), "values" to JsonObject(serialized),
        ))) + "\n").toByteArray(Charsets.UTF_8)
    }

    internal fun decode(bytes: ByteArray): Preferences = try {
        val source = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val top = json.parseToJsonElement(source).jsonObject
        require(top.keys == setOf("schema", "values") && top.getValue("schema").jsonPrimitive.int == 1)
        val values = linkedMapOf<Preferences.Key<*>, Any>()
        for ((name, entry) in top.getValue("values").jsonObject) {
            require(name.isNotBlank())
            val e = entry.jsonObject
            require(e.keys == setOf("type", "value"))
            val raw = e.getValue("value")
            val kind = e.getValue("type").jsonPrimitive.content
            val (key, decoded) = when (kind) {
                "string" -> stringPreferencesKey(name) to requireNotNull(raw.jsonPrimitive.contentOrNull)
                "long" -> longPreferencesKey(name) to raw.jsonPrimitive.long
                "int" -> intPreferencesKey(name) to raw.jsonPrimitive.int
                "boolean" -> booleanPreferencesKey(name) to raw.jsonPrimitive.boolean
                "float" -> floatPreferencesKey(name) to raw.jsonPrimitive.float
                "double" -> doublePreferencesKey(name) to raw.jsonPrimitive.double
                "stringSet" -> stringSetPreferencesKey(name) to (raw as JsonArray).map { it.jsonPrimitive.content }.toSet()
                else -> throw IllegalArgumentException("Unknown preference kind")
            }
            values[key] = decoded
        }
        Preferences(values)
    } catch (e: Exception) {
        throw CorruptionException("Invalid JSON preferences store", e)
    }
}

/** Small compatibility factory for the existing file-backed and Okio-based tests. */
object PreferenceDataStoreFactory {
    fun create(
        scope: CoroutineScope,
        migrations: List<DataMigration<Preferences>> = emptyList(),
        produceFile: () -> File,
    ): DataStore<Preferences> = DataStoreFactory.create(
        serializer = PreferencesSerializer, migrations = migrations, scope = scope, produceFile = produceFile,
    )

    fun create(storage: Storage<Preferences>, scope: CoroutineScope): DataStore<Preferences> =
        DataStoreFactory.create(storage = storage, scope = scope)
}
