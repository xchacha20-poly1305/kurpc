package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

internal const val KURPC_PACKAGE = "io.github.xchacha20_poly1305.kurpc"
private val MESSAGE_CODEC = ClassName(KURPC_PACKAGE, "MessageCodec")

/**
 * The message library the generated clients are written for: which Kotlin type a proto message
 * has there, and how one is encoded.
 */
internal interface MessageRuntime {
    /** The package of the classes generated from [file]. Its clients are generated into it too. */
    fun kotlinPackage(file: FileDescriptorProto): String

    /** The Kotlin type of the message with the full proto name [protoName] (`.package.Outer.Inner`). */
    fun type(protoName: String): TypeName

    /** An expression of type `MessageCodec<type(protoName)>`. It may call [codecFunction]. */
    fun codec(protoName: String): CodeBlock

    /** Declared privately in every generated file, for the expressions of [codec] to call. */
    fun codecFunction(): FunSpec

    /** The values of the plugin's `runtime` option. */
    enum class Kind(val option: String, val create: (List<FileDescriptorProto>) -> MessageRuntime) {
        JAVA("java", ::JavaRuntime),
        WIRE("wire", ::WireRuntime),
    }
}

/**
 * The class of every message of [files], by full proto name. A file's top-level messages are in
 * the package [packageOf] gives, inside the classes [enclosingClassesOf] gives; nested messages
 * are nested classes.
 */
internal fun messageClasses(
    files: List<FileDescriptorProto>,
    packageOf: (FileDescriptorProto) -> String,
    enclosingClassesOf: (FileDescriptorProto) -> List<String>,
): Map<String, ClassName> {
    val classes = HashMap<String, ClassName>()

    fun register(message: DescriptorProto, protoPrefix: String, kotlinPackage: String, enclosingNames: List<String>) {
        val protoName = protoPrefix + message.name
        val names = enclosingNames + message.name
        classes[protoName] = ClassName(kotlinPackage, names)
        for (nested in message.nestedTypeList) {
            register(nested, "$protoName.", kotlinPackage, names)
        }
    }

    for (file in files) {
        val protoPrefix = if (file.`package`.isEmpty()) "." else ".${file.`package`}."
        for (message in file.messageTypeList) {
            register(message, protoPrefix, packageOf(file), enclosingClassesOf(file))
        }
    }
    return classes
}

/**
 * `private fun <T> name(source): MessageCodec<T>`, returning an object whose `encode(message)`
 * and `decode(bytes)` are the given statements. Generic over the message type so every
 * descriptor of a file shares one function.
 */
internal fun codecFunction(
    name: String,
    message: TypeVariableName,
    source: ParameterSpec,
    encode: String,
    decode: String,
): FunSpec {
    val codec = TypeSpec.anonymousClassBuilder()
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
