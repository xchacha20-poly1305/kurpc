package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

/** A problem with the input that protoc reports to the user instead of a stack trace. */
internal class GeneratorException(message: String) : Exception(message)

private const val RUNTIME_OPTION = "runtime"

/** protoc hands over the plugin's options (`--kurpc_opt=`) as `name=value` pairs separated by commas. */
private fun runtimeKind(parameter: String): MessageRuntime.Kind {
    var kind = MessageRuntime.Kind.JAVA
    for (option in parameter.split(',')) {
        if (option.isEmpty()) {
            continue
        }
        val name = option.substringBefore('=')
        val value = option.substringAfter('=', missingDelimiterValue = "")
        val runtimes = MessageRuntime.Kind.entries
        if (name != RUNTIME_OPTION) {
            throw GeneratorException("unknown option \"$name\": protoc-gen-kurpc takes only \"$RUNTIME_OPTION\"")
        }
        kind = runtimes.firstOrNull { it.option == value } ?: throw GeneratorException(
            "unknown $RUNTIME_OPTION \"$value\": expected one of ${runtimes.joinToString { it.option }}",
        )
    }
    return kind
}

/**
 * One `<Service>Kurpc.kt` for every service of the files protoc asked for, written for the
 * message library the `runtime` option names (protobuf-java by default).
 */
internal fun generate(request: CodeGeneratorRequest): CodeGeneratorResponse {
    val response = CodeGeneratorResponse.newBuilder()
        // Services do not care about field presence, so proto3 `optional` needs no handling.
        .setSupportedFeatures(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong())
    try {
        val runtime = runtimeKind(request.parameter).create(request.protoFileList)
        val filesToGenerate = request.fileToGenerateList.toSet()
        for (file in request.protoFileList) {
            if (file.name !in filesToGenerate) {
                continue
            }
            for (serviceIndex in 0 until file.serviceCount) {
                val service = ServiceGenerator(file, serviceIndex, runtime).generate()
                response.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(service.relativePath)
                        .setContent(service.toString()),
                )
            }
        }
    } catch (error: GeneratorException) {
        response.clearFile()
        response.error = error.message
    }
    return response.build()
}
