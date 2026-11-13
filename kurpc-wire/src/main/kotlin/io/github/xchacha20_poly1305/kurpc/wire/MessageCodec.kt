package io.github.xchacha20_poly1305.kurpc.wire

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

internal const val KURPC_PACKAGE = "io.github.xchacha20_poly1305.kurpc"
private val MESSAGE_CODEC = ClassName(KURPC_PACKAGE, "MessageCodec")

/**
 * `private fun <T> name(source): MessageCodec<T>`, returning an object whose `encode(message)` and
 * `decode(bytes)` are the given statements. Generic over the message type so every descriptor of a
 * file shares one function.
 */
internal fun codecFunction(
    name: String,
    message: TypeVariableName,
    source: ParameterSpec,
    encode: String,
    decode: String,
): FunSpec {
    val codec =
        TypeSpec.anonymousClassBuilder()
            .addSuperinterface(MESSAGE_CODEC.parameterizedBy(message))
            .addFunction(
                FunSpec.builder("encode")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("message", message)
                    .returns(ByteArray::class)
                    .addStatement(encode)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("decode")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("bytes", ByteArray::class)
                    .returns(message)
                    .addStatement(decode)
                    .build(),
            )
            .build()
    return FunSpec.builder(name)
        .addModifiers(KModifier.PRIVATE)
        .addTypeVariable(message)
        .addParameter(source)
        .returns(MESSAGE_CODEC.parameterizedBy(message))
        .addStatement("return %L", codec)
        .build()
}
