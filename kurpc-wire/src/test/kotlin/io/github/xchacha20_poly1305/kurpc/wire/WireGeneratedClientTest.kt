package io.github.xchacha20_poly1305.kurpc.wire

import io.github.xchacha20_poly1305.kurpc.CallInvoker
import io.github.xchacha20_poly1305.kurpc.CallOptions
import io.github.xchacha20_poly1305.kurpc.MethodDescriptor
import io.github.xchacha20_poly1305.kurpc.UnaryResponse
import io.github.xchacha20_poly1305.kurpc.wire.wiretest.Outer
import io.github.xchacha20_poly1305.kurpc.wire.wiretest.WireNamesClient
import io.github.xchacha20_poly1305.kurpc.wire.wiretest.WireNamesKurpc
import io.github.xchacha20_poly1305.kurpc.wire.wiretest.renamed.Renamed
import io.github.xchacha20_poly1305.kurpc.wire.wiretest.renamed.WirePackageClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The message classes come from Wire's own compiler, compiled alongside the generated clients. */
class WireGeneratedClientTest {
    private val invoker = EchoInvoker()

    @Test
    fun descriptorsUseTheClassesWireGenerates() {
        val nested: MethodDescriptor<Outer.Inner, Outer> = WireNamesKurpc.nested
        val message = Outer(content = Outer.Inner(text = "nested"))

        assertEquals("/kurpc.wiretest.WireNames/Nested", nested.path)
        assertEquals(message, nested.responseCodec.decode(nested.responseCodec.encode(message)))
    }

    @Test
    fun streamingCallRoundTripsEveryMessage() = runBlocking {
        val messages = listOf(Outer.Inner(text = "a"), Outer.Inner(text = "b"))

        assertEquals(
            messages,
            WireNamesClient(invoker)
                .chat(flowOf(*messages.toTypedArray()))
                .toList(),
        )
    }

    @Test
    fun emptyRequestNeedsNoArgument() = runBlocking {
        val response: Unit = WireNamesClient(invoker).ping()

        assertEquals(Unit, response)
        assertEquals("/kurpc.wiretest.WireNames/Ping", invoker.paths.single())
        assertContentEquals(ByteArray(0), invoker.requests.single())
    }

    @Test
    fun wrapperIsItsValue() = runBlocking {
        assertEquals("wrapped", WireNamesClient(invoker).wrapped("wrapped"))
    }

    @Test
    fun wirePackageOptionPlacesTheClientWithItsMessages() = runBlocking {
        val message = Renamed(text = "renamed")

        assertEquals(message, WirePackageClient(invoker).echo(message))
    }

    @Test
    fun wellKnownScalarsAndStructuredValuesRoundTrip() = runBlocking {
        val client = WireNamesClient(invoker)
        assertEquals(42L, client.int64Value(42L))
        // An absent wrapper value is an empty top-level protobuf message, decoded as zero.
        assertEquals(0L, client.int64Value(null))
        assertEquals(true, client.boolValue(true))
        val struct = mapOf("name" to "Wire", "count" to 2.0, "missing" to null)
        assertEquals(struct, client.struct(struct))
        assertEquals(listOf("Wire", 2.0, null), client.listValue(listOf("Wire", 2.0, null)))
    }

    /** Answers every request message with itself. */
    private class EchoInvoker : CallInvoker {
        val paths = mutableListOf<String>()
        val requests = mutableListOf<ByteArray>()

        override suspend fun unary(
            method: String,
            request: ByteArray,
            options: CallOptions,
        ): ByteArray {
            paths += method
            requests += request
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
}
