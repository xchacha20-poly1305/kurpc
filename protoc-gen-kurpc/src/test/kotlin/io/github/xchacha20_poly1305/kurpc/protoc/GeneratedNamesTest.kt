package io.github.xchacha20_poly1305.kurpc.protoc

import EmptyPackage.Bare
import EmptyPackageNamesKurpc
import com.google.protobuf.Message
import io.github.xchacha20_poly1305.kurpc.CallInvoker
import io.github.xchacha20_poly1305.kurpc.CallOptions
import io.github.xchacha20_poly1305.kurpc.MethodDescriptor
import io.github.xchacha20_poly1305.kurpc.UnaryResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kurpc.test.naming.`is`.CallOptions as MessageCallOptions
import kurpc.test.naming.`is`.Flow as MessageFlow
import kurpc.test.naming.`is`.ImportedNamesClient
import kurpc.test.naming.`is`.ImportedNamesKurpc
import kurpc.test.naming.`is`.`when` as TopLevelWhen
import kurpc.test.naming.`when`.ExplicitNamesClient
import kurpc.test.naming.`when`.ExplicitNamesKurpc
import kurpc.test.naming.`when`.custom_API2.`object`.`when` as NestedWhen
import kurpc.test.naming.case0.MessageClashOuterClass.MessageClash as Message0
import kurpc.test.naming.case0.Naming0Kurpc
import kurpc.test.naming.case1.EnumClashOuterClass.Payload as Message1
import kurpc.test.naming.case1.Naming1Kurpc
import kurpc.test.naming.case2.ServiceClashKurpc
import kurpc.test.naming.case2.ServiceClashOuterClass.Payload as Message2
import kurpc.test.naming.case3.Naming3Kurpc
import kurpc.test.naming.case3.NestedClashOuterClass.Envelope.Layer.NestedClash as Message3
import kurpc.test.naming.case4.Naming4Kurpc
import kurpc.test.naming.case4.NestedEnumClashOuterClass.Envelope.Layer as Message4
import kurpc.test.naming.case5.Http2Api.raw_message.HTTPReply as Message5
import kurpc.test.naming.case5.Naming5Kurpc

/** These types come from real protoc output, compiled alongside the generated Kotlin clients. */
class GeneratedNamesTest {
    @Test
    fun generatedDescriptorsUseTheActualProtobufJavaClasses() {
        verify(Naming0Kurpc.echo, Message0.newBuilder().setValue("case 0").build())
        verify(Naming1Kurpc.echo, Message1.newBuilder().setValue("case 1").build())
        verify(ServiceClashKurpc.echo, Message2.newBuilder().setValue("case 2").build())
        verify(Naming3Kurpc.echo, Message3.newBuilder().setValue("case 3").build())
        verify(Naming4Kurpc.echo, Message4.newBuilder().setValue("case 4").build())
        verify(Naming5Kurpc.echo, Message5.newBuilder().setValue("case 5").build())
        verify(ExplicitNamesKurpc.`in`, NestedWhen.newBuilder().setValue("keyword").build())
        verify(EmptyPackageNamesKurpc.echo, Bare.newBuilder().setValue("empty package").build())
        verify(ImportedNamesKurpc.options, MessageCallOptions.newBuilder().setValue("options").build())
        verify(ImportedNamesKurpc.stream, MessageFlow.newBuilder().setValue("flow").build())
    }

    @Test
    fun importedTypesWithTheSameSimpleNameRemainDistinct() {
        val method: MethodDescriptor<NestedWhen, TopLevelWhen> = ImportedNamesKurpc.`in`
        val request = NestedWhen.newBuilder().setValue("request").build()
        val response = TopLevelWhen.newBuilder().setValue("response").build()
        assertEquals(request, method.requestCodec.decode(method.requestCodec.encode(request)))
        assertEquals(response, method.responseCodec.decode(method.responseCodec.encode(response)))
        verifyJavaType(request)
        verifyJavaType(response)
    }

    @Test
    fun clientsAcceptKeywordTypesAndDisambiguateTheirOwnImports() = runBlocking {
        val invoker = EchoInvoker()
        val client = ImportedNamesClient(invoker)
        val request = NestedWhen.newBuilder().setValue("cross-file").build()
        val response: TopLevelWhen = client.`in`(request)
        assertEquals(request.value, response.value)
        assertEquals(request, ExplicitNamesClient(invoker).`in`(request))

        val options = MessageCallOptions.newBuilder().setValue("message, not call options").build()
        assertEquals(options, client.options(options, CallOptions()))
        val item = MessageFlow.newBuilder().setValue("stream element").build()
        assertEquals(listOf(item), client.stream(flowOf(item)).toList())
        assertEquals(
            listOf(
                "/kurpc.test.naming.multiple.ImportedNames/In",
                "/kurpc.test.naming.explicit.ExplicitNames/In",
                "/kurpc.test.naming.multiple.ImportedNames/Options",
                "/kurpc.test.naming.multiple.ImportedNames/Stream",
            ),
            invoker.paths,
        )
    }

    // Echo wire-compatible messages without involving the native test server.
    private class EchoInvoker : CallInvoker {
        val paths = mutableListOf<String>()

        override suspend fun unary(method: String, request: ByteArray, options: CallOptions): ByteArray {
            paths += method
            return request
        }

        override fun bidiStreaming(method: String, requests: Flow<ByteArray>, options: CallOptions): Flow<ByteArray> {
            paths += method
            return requests
        }

        override suspend fun unaryCall(method: String, request: ByteArray, options: CallOptions): UnaryResponse =
            error("Unexpected unaryCall")

        override fun serverStreaming(method: String, request: ByteArray, options: CallOptions): Flow<ByteArray> =
            error("Unexpected serverStreaming")

        override suspend fun clientStreaming(
            method: String,
            requests: Flow<ByteArray>,
            options: CallOptions,
        ): ByteArray = error("Unexpected clientStreaming")
    }

    private fun <T : Message> verify(method: MethodDescriptor<T, T>, message: T) {
        assertEquals(message, method.requestCodec.decode(method.requestCodec.encode(message)))
        assertEquals(message, method.responseCodec.decode(method.responseCodec.encode(message)))
        verifyJavaType(message)
    }

    private fun verifyJavaType(message: Message) {
        val descriptor = message.descriptorForType
        val types = JavaTypes(listOf(descriptor.file.toProto()))
        // The expected class comes from protoc, independently of our naming implementation.
        assertEquals(message.javaClass.canonicalName, types.message(".${descriptor.fullName}").canonicalName)
    }
}
