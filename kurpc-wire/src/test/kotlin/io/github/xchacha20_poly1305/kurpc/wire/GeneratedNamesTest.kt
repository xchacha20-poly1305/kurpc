package io.github.xchacha20_poly1305.kurpc.wire

import Bare
import EmptyPackageNamesKurpc
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
import kurpc.test.naming.case0.MessageClash as Message0
import kurpc.test.naming.case0.Naming0Kurpc
import kurpc.test.naming.case1.Naming1Kurpc
import kurpc.test.naming.case1.Payload as Message1
import kurpc.test.naming.case2.Payload as Message2
import kurpc.test.naming.case2.ServiceClashKurpc
import kurpc.test.naming.case3.Envelope.Layer.NestedClash as Message3
import kurpc.test.naming.case3.Naming3Kurpc
import kurpc.test.naming.case4.Envelope.Layer as Message4
import kurpc.test.naming.case4.Naming4Kurpc
import kurpc.test.naming.case5.Naming5Kurpc
import kurpc.test.naming.case5.raw_message.HTTPReply as Message5
import kurpc.test.naming.`is`.CallOptions as MessageCallOptions
import kurpc.test.naming.`is`.Flow as MessageFlow
import kurpc.test.naming.`is`.ImportedNamesClient
import kurpc.test.naming.`is`.ImportedNamesKurpc
import kurpc.test.naming.`is`.WhenPayload as TopLevelWhen
import kurpc.test.naming.`when`.ExplicitNamesClient
import kurpc.test.naming.`when`.ExplicitNamesKurpc
import kurpc.test.naming.`when`.ObjectPayload.WhenPayload as NestedWhen

/** These types come from Wire output, compiled alongside the generated Kotlin clients. */
class GeneratedNamesTest {
    @Test
    fun generatedDescriptorsUseTheActualWireClasses() {
        verify(Naming0Kurpc.echo, Message0(value_ = "case 0"))
        verify(Naming1Kurpc.echo, Message1(value_ = "case 1"))
        verify(ServiceClashKurpc.echo, Message2(value_ = "case 2"))
        verify(Naming3Kurpc.echo, Message3(value_ = "case 3"))
        verify(Naming4Kurpc.echo, Message4(value_ = "case 4"))
        verify(Naming5Kurpc.echo, Message5(value_ = "case 5"))
        verify(ExplicitNamesKurpc.`in`, NestedWhen(value_ = "keyword"))
        verify(EmptyPackageNamesKurpc.echo, Bare(value_ = "empty package"))
        verify(ImportedNamesKurpc.options, MessageCallOptions(value_ = "options"))
        verify(ImportedNamesKurpc.stream, MessageFlow(value_ = "flow"))
    }

    @Test
    fun importedTypesWithTheSameSimpleNameRemainDistinct() {
        val method: MethodDescriptor<NestedWhen, TopLevelWhen> = ImportedNamesKurpc.`in`
        val request = NestedWhen(value_ = "request")
        val response = TopLevelWhen(value_ = "response")
        assertEquals(request, method.requestCodec.decode(method.requestCodec.encode(request)))
        assertEquals(response, method.responseCodec.decode(method.responseCodec.encode(response)))
    }

    @Test
    fun clientsHandleKeywordPackagesAndDisambiguateTheirOwnImports() = runBlocking {
        val invoker = EchoInvoker()
        val client = ImportedNamesClient(invoker)
        val request = NestedWhen(value_ = "cross-file")
        val response: TopLevelWhen = client.`in`(request)
        assertEquals(request.value_, response.value_)
        assertEquals(request, ExplicitNamesClient(invoker).`in`(request))

        val options = MessageCallOptions(value_ = "message, not call options")
        assertEquals(options, client.options(options, CallOptions()))
        val item = MessageFlow(value_ = "stream element")
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

        override suspend fun unary(
            method: String,
            request: ByteArray,
            options: CallOptions,
        ): ByteArray {
            paths += method
            return request
        }

        override fun bidiStreaming(
            method: String,
            requests: Flow<ByteArray>,
            options: CallOptions,
        ): Flow<ByteArray> {
            paths += method
            return requests
        }

        override suspend fun unaryCall(
            method: String,
            request: ByteArray,
            options: CallOptions,
        ): UnaryResponse = error("Unexpected unaryCall")

        override fun serverStreaming(
            method: String,
            request: ByteArray,
            options: CallOptions,
        ): Flow<ByteArray> = error("Unexpected serverStreaming")

        override suspend fun clientStreaming(
            method: String,
            requests: Flow<ByteArray>,
            options: CallOptions,
        ): ByteArray = error("Unexpected clientStreaming")
    }

    private fun <T> verify(method: MethodDescriptor<T, T>, message: T) {
        assertEquals(message, method.requestCodec.decode(method.requestCodec.encode(message)))
        assertEquals(message, method.responseCodec.decode(method.responseCodec.encode(message)))
    }
}
