package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.squareup.kotlinpoet.ClassName

/**
 * The classes protobuf-java (full or lite, which name them alike) generates for the messages of
 * [files], looked up by full proto name (`.package.Outer.Inner`). Follows the proto2 / proto3
 * rules of protoc's `ClassNameResolver`; editions are not supported, as `generate` does not
 * declare them to protoc.
 */
internal class JavaTypes(files: List<FileDescriptorProto>) {
    private val classes = HashMap<String, ClassName>()

    init {
        for (file in files) {
            val protoPrefix = if (file.`package`.isEmpty()) "." else ".${file.`package`}."
            val enclosingNames = if (file.options.javaMultipleFiles) {
                emptyList()
            } else {
                listOf(outerClassName(file))
            }
            for (message in file.messageTypeList) {
                register(message, protoPrefix, javaPackage(file), enclosingNames)
            }
        }
    }

    private fun register(
        message: DescriptorProto,
        protoPrefix: String,
        javaPackage: String,
        enclosingNames: List<String>,
    ) {
        val protoName = protoPrefix + message.name
        val names = enclosingNames + message.name
        classes[protoName] = ClassName(javaPackage, names)
        for (nested in message.nestedTypeList) {
            register(nested, "$protoName.", javaPackage, names)
        }
    }

    fun message(protoName: String): ClassName =
        classes[protoName] ?: throw GeneratorException("unknown message type $protoName")
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
