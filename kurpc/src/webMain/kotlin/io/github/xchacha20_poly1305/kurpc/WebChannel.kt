@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.xchacha20_poly1305.kurpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** What carries a server-streaming call. Unary calls always use fetch, request streams a WebSocket. */
public enum class WebStreamTransport {
    /**
     * A gRPC-Web response read as the collector takes messages, so a slow collector slows the
     * server down. Over HTTP/1.1 every open stream holds one of the browser's few connections
     * to the origin, which makes later calls wait once they are all taken.
     */
    Fetch,

    /**
     * One WebSocket per call, outside the connection limit. A WebSocket cannot slow the server
     * down: messages not collected yet are queued up to [WebChannelConfig.maxQueuedBytes] and
     * [WebChannelConfig.maxQueuedMessages], and the call fails beyond them.
     */
    WebSocket,
}

/**
 * Browser RPC settings. [baseUrl] is an HTTP(S) gRPC-Web endpoint, optionally with a path prefix.
 * WebSocket calls use the grpc-websockets protocol at [webSocketBaseUrl], or [baseUrl] by default.
 * Authentication belongs in [metadata], for example `authorization: Bearer ...`.
 * [serverStreamingTransport] applies to calls that do not choose one in [WebCallOptions].
 *
 * Limits apply to decoded message bytes and kurpc's queues, not browser-internal buffers.
 */
public data class WebChannelConfig(
    public val baseUrl: String,
    public val webSocketBaseUrl: String? = null,
    public val metadata: Metadata = Metadata.Empty,
    public val serverStreamingTransport: WebStreamTransport = WebStreamTransport.Fetch,
    public val maxMessageBytes: Int = 4 * 1024 * 1024,
    public val maxQueuedBytes: Int = 16 * 1024 * 1024,
    public val maxQueuedMessages: Int = 256,
    public val sendBufferBytes: Int = 1024 * 1024,
) {
    init {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive" }
        require(maxQueuedBytes > 0) { "maxQueuedBytes must be positive" }
        require(maxQueuedMessages > 0) { "maxQueuedMessages must be positive" }
        require(sendBufferBytes >= 2) { "sendBufferBytes must be at least 2" }
    }
}

/** Overrides [WebChannelConfig.serverStreamingTransport] for one call; null keeps the channel's. */
public class WebCallOptions(
    timeout: Duration? = null,
    metadata: Metadata = Metadata.Empty,
    public val serverStreamingTransport: WebStreamTransport? = null,
) : CallOptions(timeout, metadata)

/** Creates a browser channel. JS and Wasm share the same transport and lifecycle implementation. */
public fun Channel(config: WebChannelConfig): Channel = BrowserChannel(config)

private val bridge: WebBridge by lazy(::loadWebBridge)

private class BrowserChannel(private val config: WebChannelConfig) : Channel {
    private val calls = mutableSetOf<WebCall>()
    private var closed = false

    init {
        requireValidUrl(config.baseUrl)
        config.webSocketBaseUrl?.let(::requireValidUrl)
    }

    override suspend fun unary(method: String, request: ByteArray, options: CallOptions): ByteArray =
        unaryCall(method, request, options).message

    override suspend fun unaryCall(method: String, request: ByteArray, options: CallOptions): UnaryResponse =
        singleResponse(method, request, null, options)

    override suspend fun clientStreaming(method: String, requests: Flow<ByteArray>, options: CallOptions): ByteArray =
        singleResponse(method, null, requests, options).message

    override fun serverStreaming(method: String, request: ByteArray, options: CallOptions): Flow<ByteArray> =
        stream(method, request, null, options)

    override fun bidiStreaming(method: String, requests: Flow<ByteArray>, options: CallOptions): Flow<ByteArray> =
        stream(method, null, requests, options)

    private suspend fun singleResponse(
        method: String,
        request: ByteArray?,
        requests: Flow<ByteArray>?,
        options: CallOptions,
    ): UnaryResponse {
        val call = start(method, useSocket = requests != null, request, options)
        try {
            return coroutineScope {
                val sender = sendRequests(call, requests)
                var message: ByteArray? = null
                var headers = Metadata.Empty
                while (true) {
                    val response = call.next().await().checked()
                    if (response.kind == "end") {
                        sender?.cancel()
                        return@coroutineScope UnaryResponse(
                            message ?: throw protocolError("missing response message"),
                            headers,
                            response.trailers.toMetadata(),
                        )
                    }
                    if (message != null) {
                        throw protocolError("multiple responses to a single-response RPC")
                    }
                    message = response.message!!.toByteArray()
                    headers = response.headers.toMetadata()
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } finally {
            release(call)
        }
    }

    private fun stream(
        method: String,
        request: ByteArray?,
        requests: Flow<ByteArray>?,
        options: CallOptions,
    ): Flow<ByteArray> = flow {
        val transport = (options as? WebCallOptions)?.serverStreamingTransport ?: config.serverStreamingTransport
        val call = start(
            method,
            useSocket = requests != null || transport == WebStreamTransport.WebSocket,
            request,
            options,
        )
        try {
            coroutineScope {
                val sender = sendRequests(call, requests)
                while (true) {
                    val response = call.next().await().checked()
                    if (response.kind == "end") break
                    emit(response.message!!.toByteArray())
                }
                sender?.cancel()
            }
        } finally {
            release(call)
        }
    }

    private fun start(method: String, useSocket: Boolean, request: ByteArray?, options: CallOptions): WebCall {
        if (closed) throw ChannelClosedException()
        val headers = bridge.metadata()
        for ((key, value) in (config.metadata + options.metadata).entries) {
            require(key.isNotEmpty() && key.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.' }) {
                "invalid metadata key"
            }
            if (value is Metadata.Value.Text) {
                require(value.text.all { it.code in 32..126 }) { "text metadata must contain printable ASCII" }
            }
            headers.add(
                key, when (value) {
                    is Metadata.Value.Text -> value.text
                    is Metadata.Value.Binary -> Base64.encode(value.bytes)
                }
            )
        }
        val timeout = options.timeout
        val timeoutMs = if (timeout == null || timeout.isInfinite() || timeout.isNegative()) {
            -1.0
        } else {
            timeout.toDouble(DurationUnit.MILLISECONDS)
        }
        return bridge.open(
            config.baseUrl, config.webSocketBaseUrl, method, useSocket, request?.toWebBytes(), headers, timeoutMs,
            config.maxMessageBytes, config.maxQueuedBytes, config.maxQueuedMessages, config.sendBufferBytes,
        ).also { calls.add(it) }
    }

    private fun release(call: WebCall) {
        call.cancel()
        calls.remove(call)
    }

    override fun close() {
        if (closed) return
        closed = true
        calls.forEach { it.close() }
        calls.clear()
    }
}

private fun CoroutineScope.sendRequests(call: WebCall, requests: Flow<ByteArray>?): Job? = requests?.let { input ->
    val scope = this
    launch {
        try {
            // The receiver consumes the terminal status after any queued messages.
            // Letting the sender throw it could interrupt that delivery.
            input.takeWhile { message -> call.send(message.toWebBytes()).await().kind == "sent" }.collect {}
            call.halfClose()
        } catch (error: CancellationException) {
            // An input flow can itself throw CancellationException while our
            // scope is still active. That must also end the response collection.
            if (isActive) scope.cancel(error)
            throw error
        }
    }
}

private fun requireValidUrl(url: String) {
    val error = bridge.validateUrl(url)
    require(error.isEmpty()) { error }
}

private fun WebMetadata.toMetadata(): Metadata {
    var result = Metadata.Empty
    for (index in 0 until size) {
        val key = key(index)
        val value = value(index)
        if (key.endsWith("-bin")) {
            // Fetch can combine binary headers. Unlike text headers their base64
            // encoding cannot contain a comma, so these boundaries are recoverable.
            for (part in value.split(',')) {
                val encoded = part.trim()
                val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
                val bytes = try {
                    Base64.decode(padded)
                } catch (_: IllegalArgumentException) {
                    throw protocolError("invalid binary metadata for $key")
                }
                result += Metadata.ofBinary(key to bytes)
            }
        } else {
            result += Metadata.of(key to value)
        }
    }
    return result
}

private fun WebResult.checked(): WebResult {
    if (kind != "error") return this
    when (code) {
        -1 -> throw TransportException(description)
        -2 -> throw ChannelClosedException(description)
        -3 -> throw IllegalArgumentException(description)
        else -> {
            val metadata = trailers.toMetadata()
            throw StatusException(
                Status.Code.fromValue(code), description,
                metadata.binary("grpc-status-details-bin") ?: ByteArray(0), metadata,
            )
        }
    }
}

private fun protocolError(message: String): StatusException =
    StatusException(Status.Code.INTERNAL, message, ByteArray(0), Metadata.Empty)
