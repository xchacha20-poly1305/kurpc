package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeVariableName

private val MESSAGE_LITE = ClassName("com.google.protobuf", "MessageLite")
private val PARSER = ClassName("com.google.protobuf", "Parser")

private const val CODEC_FUNCTION = "protobufCodec"

/**
 * The classes protobuf-java (full or lite, which name them alike) generates, encoded through
 * their `parser()`. Follows the proto2 / proto3 rules of protoc's `ClassNameResolver`; editions
 * are not supported, as `generate` does not declare them to protoc.
 */
internal class JavaRuntime(files: List<FileDescriptorProto>) : MessageRuntime {
    private val classes: Map<String, ClassName> = messageClasses(files, ::kotlinPackage) { file ->
        if (file.options.javaMultipleFiles) emptyList() else listOf(outerClassName(file))
    }

    override fun kotlinPackage(file: FileDescriptorProto): String = javaPackage(file)

    override fun type(protoName: String): ClassName =
        classes[protoName] ?: throw GeneratorException("unknown message type $protoName")

    override fun codec(protoName: String): CodeBlock =
        CodeBlock.of("%N(%T.parser())", CODEC_FUNCTION, type(protoName))

    override fun codecFunction(): FunSpec {
        val message = TypeVariableName("T", MESSAGE_LITE)
        return codecFunction(
            CODEC_FUNCTION,
            message,
            ParameterSpec("parser", PARSER.parameterizedBy(message)),
            encode = "return message.toByteArray()",
            decode = "return parser.parseFrom(bytes)",
        )
    }
}

internal fun javaPackage(file: FileDescriptorProto): String =
    if (file.options.hasJavaPackage()) file.options.javaPackage else file.`package`

/** `java_outer_classname`, or the file name in camel case with `OuterClass` on a clash. */
internal fun outerClassName(file: FileDescriptorProto): String {
    if (file.options.hasJavaOuterClassname()) {
        return file.options.javaOuterClassname
    }
    val baseName = file.name.substringAfterLast('/').removeSuffix(".protodevel").removeSuffix(".proto")
    val className = underscoresToCamelCase(baseName)
    return if (definesClass(file, className)) "${className}OuterClass" else className
}

private fun definesClass(file: FileDescriptorProto, className: String): Boolean =
    file.enumTypeList.any { it.name == className } ||
        file.serviceList.any { it.name == className } ||
        file.messageTypeList.any { definesClass(it, className) }

private fun definesClass(message: DescriptorProto, className: String): Boolean =
    message.name == className ||
        message.enumTypeList.any { it.name == className } ||
        message.nestedTypeList.any { definesClass(it, className) }

/**
 * protoc's `UnderscoresToCamelCase(name, true)`: anything but an ASCII letter or digit separates
 * words, and a digit also starts a new word.
 */
internal fun underscoresToCamelCase(name: String): String {
    val result = StringBuilder(name.length)
    var capitalizeNext = true
    for (char in name) {
        when (char) {
            in 'a'..'z' -> {
                result.append(if (capitalizeNext) char.uppercaseChar() else char)
                capitalizeNext = false
            }
            in 'A'..'Z' -> {
                result.append(char)
                capitalizeNext = false
            }
            in '0'..'9' -> {
                result.append(char)
                capitalizeNext = true
            }
            else -> capitalizeNext = true
        }
    }
    return result.toString()
}
