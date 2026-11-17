package com.example.sostavslova.data.jsonstore

import androidx.datastore.core.DataMigration
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/**
 * Read the old AndroidX Preferences wire format once, without bundling protobuf.
 * The old file is deliberately retained: it is a recovery source, not a cache.
 */
class LegacyPreferencesMigration(
    private val legacyFile: File,
    private val newFileDidNotExistAtStartup: Boolean,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        newFileDidNotExistAtStartup && legacyFile.isFile && currentData.asMap().isEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences {
        check(currentData.asMap().isEmpty()) { "Refusing to overwrite saved JSON progress" }
        if (!legacyFile.isFile) throw IOException("Previous preferences file disappeared")
        if (legacyFile.length() > MAX_LEGACY_BYTES) throw IOException("Legacy preferences file is too large")
        return LegacyPreferencesReader.decode(legacyFile.readBytes())
    }

    override suspend fun cleanUp() { /* User-owned legacy state is intentionally untouched. */ }
}

/** Minimal strict decoder for AndroidX PreferenceMap, not a general Protobuf runtime. */
internal object LegacyPreferencesReader {
    fun decode(bytes: ByteArray): Preferences {
        try {
            val top = Reader(bytes)
            val values = linkedMapOf<Preferences.Key<*>, Any>()
            val names = mutableSetOf<String>()
            while (top.hasNext()) {
                val tag = top.tag()
                if (tag != 10) throw IOException("Unexpected Preferences field")
                val entry = Reader(top.bytes())
                var name: String? = null
                var value: Pair<Preferences.Key<*>, Any>? = null
                var valueBytes: ByteArray? = null
                while (entry.hasNext()) {
                    when (entry.tag()) {
                        10 -> { check(name == null); name = entry.string() }
                        18 -> { check(valueBytes == null); valueBytes = entry.bytes() }
                        else -> throw IOException("Unexpected map entry field")
                    }
                }
                val keyName = name ?: throw IOException("Missing preferences key")
                if (keyName.isBlank() || !names.add(keyName)) throw IOException("Duplicate/empty preferences key")
                value = decodeValue(keyName, valueBytes ?: throw IOException("Missing preferences value"))
                values[value.first] = value.second
            }
            return Preferences(values)
        } catch (e: Exception) {
            throw IOException("Unable to migrate legacy preferences without data loss", e)
        }
    }

    private fun decodeValue(name: String, bytes: ByteArray): Pair<Preferences.Key<*>, Any> {
        val value = Reader(bytes)
        if (!value.hasNext()) throw IOException("Empty preferences value")
        val result: Pair<Preferences.Key<*>, Any> = when (value.tag()) {
            8 -> booleanPreferencesKey(name) to (value.varint() != 0L)
            21 -> floatPreferencesKey(name) to Float.fromBits(value.fixed32())
            24 -> intPreferencesKey(name) to value.varint().toInt()
            32 -> longPreferencesKey(name) to value.varint()
            42 -> stringPreferencesKey(name) to value.string()
            50 -> {
                val set = Reader(value.bytes())
                val entries = linkedSetOf<String>()
                while (set.hasNext()) {
                    if (set.tag() != 10) throw IOException("Unexpected string set field")
                    entries += set.string()
                }
                stringSetPreferencesKey(name) to entries
            }
            57 -> doublePreferencesKey(name) to Double.fromBits(value.fixed64())
            else -> throw IOException("Unknown legacy preference kind")
        }
        if (value.hasNext()) throw IOException("Multiple values in one preference")
        return result
    }

    private class Reader(private val input: ByteArray) {
        private var offset = 0
        fun hasNext(): Boolean = offset < input.size

        fun tag(): Int {
            val value = varint()
            if (value <= 0 || value > Int.MAX_VALUE) throw IOException("Invalid field tag")
            return value.toInt()
        }

        fun varint(): Long {
            var number = 0L
            for (shift in 0..63 step 7) {
                if (!hasNext()) throw IOException("Truncated varint")
                val byte = input[offset++].toInt() and 0xff
                if (shift == 63 && byte > 1) throw IOException("Overflowed varint")
                number = number or ((byte.toLong() and 0x7fL) shl shift)
                if (byte and 0x80 == 0) return number
            }
            throw IOException("Overlong varint")
        }

        fun bytes(): ByteArray {
            val size = varint()
            if (size < 0 || size > input.size - offset) throw IOException("Truncated field")
            return input.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
        }

        fun string(): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes())).toString()

        fun fixed32(): Int = fixed(4).int
        fun fixed64(): Long = fixed(8).long

        private fun fixed(size: Int): ByteBuffer {
            if (input.size - offset < size) throw IOException("Truncated fixed field")
            return ByteBuffer.wrap(input, offset, size).order(ByteOrder.LITTLE_ENDIAN).also { offset += size }
        }
    }
}

private const val MAX_LEGACY_BYTES = 16L * 1024 * 1024
