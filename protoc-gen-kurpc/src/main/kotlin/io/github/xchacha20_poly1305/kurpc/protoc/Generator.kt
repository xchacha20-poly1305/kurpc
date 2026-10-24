package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

/** A problem with the input that protoc reports to the user instead of a stack trace. */
internal class GeneratorException(message: String) : Exception(message)

/** One `<Service>Kurpc.kt` for every service of the files protoc asked for. */
internal fun generate(request: CodeGeneratorRequest): CodeGeneratorResponse {
    val response = CodeGeneratorResponse.newBuilder()
        // Services do not care about field presence, so proto3 `optional` needs no handling.
        .setSupportedFeatures(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong())
    try {
        if (request.parameter.isNotEmpty()) {
            throw GeneratorException("protoc-gen-kurpc takes no options, got \"${request.parameter}\"")
        }
        val types = JavaTypes(request.protoFileList)
        val filesToGenerate = request.fileToGenerateList.toSet()
        for (file in request.protoFileList) {
            if (file.name !in filesToGenerate) {
                continue
            }
            for (serviceIndex in 0 until file.serviceCount) {
                val service = ServiceGenerator(file, serviceIndex, types).generate()
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
