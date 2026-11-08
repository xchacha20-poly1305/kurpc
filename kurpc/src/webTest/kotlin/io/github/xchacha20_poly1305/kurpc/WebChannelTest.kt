@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.xchacha20_poly1305.kurpc

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private const val METHOD = "/test.Service/Method"
private const val BASE_URL = "http://example.org/api"
private const val FETCH = "fetch"
private const val SOCKET = "socket"

/** Several 64 KiB pages, so the Wasm copy has to grow linear memory. */
private const val LARGE_MESSAGE_BYTES = 1024 * 1024

class WebChannelTest {
    /** Longer than a byte's range, so every byte value crosses to JavaScript and back. */
    private val message = ByteArray(300) { it.toByte() }

    @BeforeTest
    fun installTransports() = installEchoTransports()

    @Test
    fun unaryUsesFetch() = runTest {
        val channel = Channel(WebChannelConfig(BASE_URL))

        assertContentEquals(message, channel.unary(METHOD, message))
        assertEquals(FETCH, usedTransports())
    }

    @Test
    fun messageLargerThanAWasmMemoryPageSurvivesTheCopy() = runTest {
        val channel = Channel(WebChannelConfig(BASE_URL))
        val large = ByteArray(LARGE_MESSAGE_BYTES) { (it * 31).toByte() }

        assertContentEquals(large, channel.unary(METHOD, large))
    }

    @Test
    fun serverStreamingUsesFetchByDefault() = runTest {
        val channel = Channel(WebChannelConfig(BASE_URL))

        val responses = channel.serverStreaming(METHOD, message).toList()

        assertContentEquals(message, responses.single())
        assertEquals(FETCH, usedTransports())
    }

    @Test
    fun serverStreamingUsesTheTransportOfItsChannel() = runTest {
        val channel = Channel(
            WebChannelConfig(BASE_URL, serverStreamingTransport = WebStreamTransport.WebSocket),
        )

        val responses = channel.serverStreaming(METHOD, message).toList()

        assertContentEquals(message, responses.single())
        assertEquals(SOCKET, usedTransports())
    }

    @Test
    fun callOptionsOverrideTheTransportOfTheChannel() = runTest {
        val channel = Channel(
            WebChannelConfig(BASE_URL, serverStreamingTransport = WebStreamTransport.WebSocket),
        )
        val options = WebCallOptions(serverStreamingTransport = WebStreamTransport.Fetch)

        channel.serverStreaming(METHOD, message, options).toList()

        assertEquals(FETCH, usedTransports())
    }

    @Test
    fun requestStreamsUseWebSocket() = runTest {
        val channel = Channel(WebChannelConfig(BASE_URL))
        val second = byteArrayOf(1)

        val responses = channel.bidiStreaming(METHOD, flowOf(message, second)).toList()

        assertEquals(2, responses.size)
        assertContentEquals(message, responses[0])
        assertContentEquals(second, responses[1])
        assertEquals(SOCKET, usedTransports())
    }
}

/**
 * Replaces fetch and WebSocket with servers that answer every request message with itself and
 * end the call with an OK status. Kotlin/JS parses this code as ES5.
 */
@JsFun(
    """function() {
    function frame(flags, payload) {
        var result = new Uint8Array(payload.length + 5);
        result[0] = flags;
        new DataView(result.buffer).setUint32(1, payload.length);
        result.set(payload, 5);
        return result;
    }
    var status = frame(128, new TextEncoder().encode("grpc-status: 0\r\n"));
    globalThis.kurpcTestTransports = [];
    globalThis.fetch = function(url, init) {
        globalThis.kurpcTestTransports.push("fetch");
        var body = new ReadableStream({
            start: function(controller) {
                controller.enqueue(init.body);
                controller.enqueue(status);
                controller.close();
            }
        });
        return Promise.resolve(new Response(body));
    };
    function EchoSocket(url, protocol) {
        var socket = this;
        socket.protocol = protocol;
        socket.readyState = 1;
        socket.bufferedAmount = 0;
        socket.receivedMetadata = false;
        globalThis.kurpcTestTransports.push("socket");
        setTimeout(function() { socket.onopen(); }, 0);
    }
    EchoSocket.CLOSING = 2;
    EchoSocket.prototype.send = function(data) {
        var socket = this;
        if (!socket.receivedMetadata) {
            socket.receivedMetadata = true;
            return;
        }
        var halfClose = data.length === 1 && data[0] === 1;
        var reply = halfClose ? status : data.slice(1);
        setTimeout(function() { socket.onmessage({data: reply.buffer}); }, 0);
    };
    EchoSocket.prototype.close = function() {
        this.readyState = 3;
    };
    globalThis.WebSocket = EchoSocket;
}""",
)
private external fun installEchoTransports()

@JsFun("function() { return globalThis.kurpcTestTransports.join(','); }")
private external fun usedTransports(): String
