package io.github.xchacha20_poly1305.kurpc

import kotlin.io.encoding.Base64

/**
 * Immutable gRPC metadata.
 *
 * ASCII keys carry [Value.Text]. Keys ending in `-bin` carry [Value.Binary]; those bytes are
 * base64 on the JNI boundary and raw everywhere else. Order is preserved, and the same key may
 * appear more than once.
 */
public class Metadata private constructor(
    /** Every entry in wire order; a key may repeat. */
    public val entries: List<Pair<String, Value>>,
) {
    public sealed class Value {
        public class Text(public val text: String) : Value() {
            override fun equals(other: Any?): Boolean = other is Text && other.text == text

            override fun hashCode(): Int = text.hashCode()

            override fun toString(): String = text
        }

        public class Binary(bytes: ByteArray) : Value() {
            public val bytes: ByteArray = bytes.copyOf()

            override fun equals(other: Any?): Boolean = other is Binary && other.bytes.contentEquals(bytes)

            override fun hashCode(): Int = this.bytes.contentHashCode()

            override fun toString(): String = "Binary(${this.bytes.size})"
        }
    }

    public fun isEmpty(): Boolean = entries.isEmpty()

    /** First ASCII value of [key], or null. */
    public fun text(key: String): String? {
        for ((entryKey, value) in entries) {
            if (entryKey == key && value is Value.Text) {
                return value.text
            }
        }
        return null
    }

    /** First binary value of [key], copied, or null. */
    public fun binary(key: String): ByteArray? {
        for ((entryKey, value) in entries) {
            if (entryKey == key && value is Value.Binary) {
                return value.bytes.copyOf()
            }
        }
        return null
    }

    public operator fun plus(other: Metadata): Metadata = Metadata(entries + other.entries)

    override fun equals(other: Any?): Boolean {
        if (other !is Metadata || entries.size != other.entries.size) {
            return false
        }
        for (index in entries.indices) {
            if (entries[index] != other.entries[index]) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var hash = 1
        for (entry in entries) {
            hash = 31 * hash + entry.hashCode()
        }
        return hash
    }

    override fun toString(): String = entries.joinToString(prefix = "Metadata(", postfix = ")") { (key, value) ->
        "$key=$value"
    }

    /** `[k0, v0, k1, v1, ...]`, with `-bin` values base64-encoded. The inverse is [fromNative]. */
    internal fun toNativeFlat(): Array<String> {
        if (isEmpty()) {
            return emptyArray()
        }
        val flat = ArrayList<String>(entries.size * 2)
        for ((key, value) in entries) {
            flat.add(key)
            flat.add(
                when (value) {
                    is Value.Text -> value.text
                    is Value.Binary -> Base64.encode(value.bytes)
                },
            )
        }
        return flat.toTypedArray()
    }

    public companion object {
        public val Empty: Metadata = Metadata(emptyList())

        public fun of(vararg pairs: Pair<String, String>): Metadata {
            val entries = ArrayList<Pair<String, Value>>(pairs.size)
            for ((key, value) in pairs) {
                requireTextKey(key)
                entries.add(key to Value.Text(value))
            }
            return Metadata(entries)
        }

        public fun ofBinary(vararg pairs: Pair<String, ByteArray>): Metadata {
            val entries = ArrayList<Pair<String, Value>>(pairs.size)
            for ((key, value) in pairs) {
                requireBinaryKey(key)
                entries.add(key to Value.Binary(value))
            }
            return Metadata(entries)
        }

        /** Inverse of [toNativeFlat]. `-bin` values are base64; padding is optional. */
        internal fun fromNative(flat: Array<String>): Metadata {
            if (flat.size % 2 != 0) {
                throw IllegalArgumentException("metadata must hold key-value pairs")
            }
            val decoded = ArrayList<Pair<String, Value>>(flat.size / 2)
            var index = 0
            while (index < flat.size) {
                val key = flat[index]
                val raw = flat[index + 1]
                val value = if (key.endsWith("-bin")) {
                    Value.Binary(Base64.decode(raw))
                } else {
                    Value.Text(raw)
                }
                decoded.add(key to value)
                index += 2
            }
            return Metadata(decoded)
        }
    }
}

private fun requireTextKey(key: String) {
    if (key.isEmpty() || key.endsWith("-bin")) {
        throw IllegalArgumentException("metadata key $key is not an ASCII key")
    }
}

private fun requireBinaryKey(key: String) {
    if (key.length <= 4 || !key.endsWith("-bin")) {
        throw IllegalArgumentException("metadata key $key is not a binary key")
    }
}
