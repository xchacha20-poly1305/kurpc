package io.github.xchacha20_poly1305.kurpc.wire

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.wire.kotlin.KotlinGenerator
import com.squareup.wire.schema.ProtoType
import com.squareup.wire.schema.Schema

private const val WIRE_PACKAGE = "com.squareup.wire"
private val PROTO_ADAPTER = ClassName(WIRE_PACKAGE, "ProtoAdapter")
private val BYTE_STRING = ClassName("okio", "ByteString")

private const val CODEC_FUNCTION = "wireCodec"

/**
 * A well-known type Wire has no generated class for: its Kotlin type and `ProtoAdapter` constant.
 */
private class BuiltIn(val type: TypeName, val adapter: String)

/** `BUILT_IN_TYPES` and `ProtoType.getAdapterName` of Wire's `KotlinGenerator`, for messages. */
private val BUILT_INS: Map<String, BuiltIn> =
    mapOf(
        ".google.protobuf.Empty" to BuiltIn(UNIT, "EMPTY"),
        ".google.protobuf.Duration" to BuiltIn(ClassName(WIRE_PACKAGE, "Duration"), "DURATION"),
        ".google.protobuf.Timestamp" to BuiltIn(ClassName(WIRE_PACKAGE, "Instant"), "INSTANT"),
        ".google.protobuf.FieldMask" to BuiltIn(ClassName(WIRE_PACKAGE, "FieldMask"), "FIELD_MASK"),
        ".google.protobuf.Struct" to
                BuiltIn(MAP.parameterizedBy(STRING, STAR).copy(nullable = true), "STRUCT_MAP"),
        ".google.protobuf.ListValue" to
                BuiltIn(LIST.parameterizedBy(STAR).copy(nullable = true), "STRUCT_LIST"),
        ".google.protobuf.Value" to BuiltIn(ANY.copy(nullable = true), "STRUCT_VALUE"),
        ".google.protobuf.DoubleValue" to BuiltIn(DOUBLE.copy(nullable = true), "DOUBLE_VALUE"),
        ".google.protobuf.FloatValue" to BuiltIn(FLOAT.copy(nullable = true), "FLOAT_VALUE"),
        ".google.protobuf.Int64Value" to BuiltIn(LONG.copy(nullable = true), "INT64_VALUE"),
        ".google.protobuf.UInt64Value" to BuiltIn(LONG.copy(nullable = true), "UINT64_VALUE"),
        ".google.protobuf.Int32Value" to BuiltIn(INT.copy(nullable = true), "INT32_VALUE"),
        ".google.protobuf.UInt32Value" to BuiltIn(INT.copy(nullable = true), "UINT32_VALUE"),
        ".google.protobuf.BoolValue" to BuiltIn(BOOLEAN.copy(nullable = true), "BOOL_VALUE"),
        ".google.protobuf.StringValue" to BuiltIn(STRING.copy(nullable = true), "STRING_VALUE"),
        ".google.protobuf.BytesValue" to BuiltIn(BYTE_STRING.copy(nullable = true), "BYTES_VALUE"),
    )

/**
 * The classes Wire's Kotlin generator emits with its default options: one top-level class per
 * message, encoded by its `ADAPTER`. Options that rename classes (`nameSuffix`, `mutableTypes`, a
 * message called `Builder` under `javaInterop`) are not followed.
 */
internal class WireRuntime(private val schema: Schema) {
    private val generator = KotlinGenerator(schema)

    fun type(protoName: ProtoType): TypeName {
        return BUILT_INS[".$protoName"]?.type ?: messageClass(protoName)
    }

    fun codec(protoName: ProtoType): CodeBlock {
        val builtIn = BUILT_INS[".$protoName"]
        return if (builtIn != null) {
            CodeBlock.of("%N(%T.%L)", CODEC_FUNCTION, PROTO_ADAPTER, builtIn.adapter)
        } else {
            CodeBlock.of("%N(%T.ADAPTER)", CODEC_FUNCTION, messageClass(protoName))
        }
    }

    fun codecFunction(): FunSpec {
        // Unbounded: Wire's types for well-known messages include Unit and nullable values.
        val message = TypeVariableName("T")
        return codecFunction(
            CODEC_FUNCTION,
            message,
            ParameterSpec("adapter", PROTO_ADAPTER.parameterizedBy(message)),
            encode = "return adapter.encode(message)",
            decode = "return adapter.decode(bytes)",
        )
    }

    private fun messageClass(protoName: ProtoType): ClassName {
        return if (protoName.toString() == "google.protobuf.Any") {
            ClassName(WIRE_PACKAGE, "AnyMessage")
        } else {
            generator.generatedTypeName(
                requireNotNull(schema.getType(protoName)) { "Unknown message type $protoName" },
            )
        }
    }
}
