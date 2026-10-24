package io.github.xchacha20_poly1305.kurpc.protoc

import io.github.xchacha20_poly1305.kurpc.CallInvoker
import io.github.xchacha20_poly1305.kurpc.CallOptions
import io.github.xchacha20_poly1305.kurpc.Status
import io.github.xchacha20_poly1305.kurpc.StatusException
import io.github.xchacha20_poly1305.kurpc.UnaryResponse
import io.github.xchacha20_poly1305.kurpc.protoc.test.TestClient
import io.github.xchacha20_poly1305.kurpc.protoc.test.TestKurpc
import io.github.xchacha20_poly1305.kurpc.protoc.test.TestOuterClass.Payload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class GeneratedClientTest {
    @Test
    fun descriptorsCarryTheMethodPaths() {
        assertEquals("kurpc.test.Test", TestKurpc.SERVICE_NAME)
        assertEquals("/kurpc.test.Test/Echo", TestKurpc.echo.path)
        assertEquals("/kurpc.test.Test/Chat", TestKurpc.chat.path)
    }

    @Test
    fun unaryCallRoundTripsTheMessage() = test {
        TestServer().use { server ->
            server.channel().use { channel ->
                val reply = TestClient(channel).echo(payload("hello"), CallOptions(timeout = 5.seconds))
                assertEquals("hello", reply.text)
            }
        }
    }

    @Test
    fun bidiStreamingRoundTripsEveryMessage() = test {
        TestServer().use { server ->
            server.channel().use { channel ->
                val replies = TestClient(channel).chat(flowOf(payload("a"), payload("b"))).toList()
                assertEquals(listOf("a", "b"), replies.map { it.text })
            }
        }
    }

    @Test
    fun streamingCallsEncodeRequestsAndDecodeResponses() = test {
        val client = TestClient(RecordingInvoker())
        assertEquals(
            listOf("/kurpc.test.Test/Count a"),
            client.count(payload("a")).toList().map { it.text },
        )
        assertEquals(
            "/kurpc.test.Test/Collect a,b",
            client.collect(flowOf(payload("a"), payload("b"))).text,
        )
    }

    @Test
    fun undecodableResponseFailsWithInternal() = test {
        val garbage = object : RecordingInvoker() {
            override suspend fun unary(method: String, request: ByteArray, options: CallOptions): ByteArray =
                byteArrayOf(0x0a, 0x7f)
        }
        val error = assertFailsWith<StatusException> { TestClient(garbage).echo(payload("x")) }
        assertEquals(Status.Code.INTERNAL, error.code)
    }

    /** Answers every call with one `Payload` naming the method and the request texts. */
    private open class RecordingInvoker : CallInvoker {
        override suspend fun unary(method: String, request: ByteArray, options: CallOptions): ByteArray =
            answer(method, listOf(request))

        override suspend fun unaryCall(method: String, request: ByteArray, options: CallOptions): UnaryResponse =
            throw UnsupportedOperationException()

        override fun serverStreaming(method: String, request: ByteArray, options: CallOptions): Flow<ByteArray> =
            flowOf(answer(method, listOf(request)))

        override suspend fun clientStreaming(
            method: String,
            requests: Flow<ByteArray>,
            options: CallOptions,
        ): ByteArray = answer(method, requests.toList())

        override fun bidiStreaming(method: String, requests: Flow<ByteArray>, options: CallOptions): Flow<ByteArray> =
            requests.map { answer(method, listOf(it)) }

        private fun answer(method: String, requests: List<ByteArray>): ByteArray {
            val texts = requests.joinToString(",") { Payload.parseFrom(it).text }
            return payload("$method $texts").toByteArray()
        }
    }
}

private fun payload(text: String): Payload = Payload.newBuilder().setText(text).build()

private fun test(body: suspend () -> Unit) = runBlocking {
    withTimeout(30.seconds) { body() }
}
