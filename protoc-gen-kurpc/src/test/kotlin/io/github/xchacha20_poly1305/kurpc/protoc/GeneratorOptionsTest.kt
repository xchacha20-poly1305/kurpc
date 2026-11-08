package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GeneratorOptionsTest {
    @Test
    fun protobufJavaIsTheDefaultRuntime() {
        val source = generate(request(parameter = "")).fileList.single().content

        assertContains(source, "protobufCodec(Messages.Payload.parser())")
    }

    @Test
    fun runtimeOptionSelectsWire() {
        val source = generate(request(parameter = "runtime=wire")).fileList.single().content

        assertContains(source, "wireCodec(Payload.ADAPTER)")
    }

    @Test
    fun unknownRuntimeIsReportedToProtocWithoutOutput() {
        val response = generate(request(parameter = "runtime=moshi"))

        assertFailedWith(response, "unknown runtime \"moshi\": expected one of java, wire")
    }

    @Test
    fun unknownOptionIsReportedToProtocWithoutOutput() {
        val response = generate(request(parameter = "runtime=wire,lite"))

        assertFailedWith(response, "unknown option \"lite\": protoc-gen-kurpc takes only \"runtime\"")
    }

    private fun assertFailedWith(response: CodeGeneratorResponse, error: String) {
        assertEquals(error, response.error)
        assertEquals(0, response.fileCount)
    }

    /** `messages.proto`: `service Echo { rpc Send(Payload) returns (Payload); }` in package `pkg`. */
    private fun request(parameter: String): CodeGeneratorRequest {
        val method = MethodDescriptorProto.newBuilder()
            .setName("Send")
            .setInputType(".pkg.Payload")
            .setOutputType(".pkg.Payload")
        val file = FileDescriptorProto.newBuilder()
            .setName("messages.proto")
            .setPackage("pkg")
            .addMessageType(DescriptorProto.newBuilder().setName("Payload"))
            .addService(ServiceDescriptorProto.newBuilder().setName("Echo").addMethod(method))
        return CodeGeneratorRequest.newBuilder()
            .setParameter(parameter)
            .addFileToGenerate("messages.proto")
            .addProtoFile(file)
            .build()
    }
}
