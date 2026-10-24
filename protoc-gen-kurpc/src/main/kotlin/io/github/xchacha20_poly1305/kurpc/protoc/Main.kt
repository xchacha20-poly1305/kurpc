package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest

/** protoc plugin entry: a `CodeGeneratorRequest` on stdin, a `CodeGeneratorResponse` on stdout. */
fun main() {
    val request = CodeGeneratorRequest.parseFrom(System.`in`)
    generate(request).writeTo(System.out)
    System.out.flush()
}
