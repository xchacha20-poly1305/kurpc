package io.github.xchacha20_poly1305.kurpc

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

public data class UnaryResponse(
    public val message: ByteArray,
    public val headers: Metadata,
    public val trailers: Metadata,
) {
    override fun equals(other: Any?): Boolean =
        other is UnaryResponse &&
            other.message.contentEquals(message) &&
            other.headers == headers &&
            other.trailers == trailers

    override fun hashCode(): Int {
        var hash = message.contentHashCode()
        hash = 31 * hash + headers.hashCode()
        hash = 31 * hash + trailers.hashCode()
        return hash
    }
}

public data class CallOptions(
    public val timeout: Duration? = null,
    public val metadata: Metadata = Metadata.Empty,
    public val waitForReady: Boolean = true,
)

public expect class Channel public constructor(config: ChannelConfig) : AutoCloseable {
    /**
     * The response body. Empty protobuf messages are empty arrays, never null.
     */
    public suspend fun unary(
        method: String,
        request: ByteArray,
        options: CallOptions = CallOptions(),
    ): ByteArray

    public suspend fun unaryCall(
        method: String,
        request: ByteArray,
        options: CallOptions = CallOptions(),
    ): UnaryResponse

    /**
     * Cold: every collection starts a new call. Cancelling the collection cancels the call.
     * A status of OK completes the flow; any other outcome fails it.
     */
    public fun serverStreaming(
        method: String,
        request: ByteArray,
        options: CallOptions = CallOptions(),
    ): Flow<ByteArray>

    /**
     * Sends every element of [requests], then half-closes, and returns the single response.
     * If the server answers before [requests] ends, the rest is not collected. A failure of
     * [requests] cancels the call and is rethrown.
     */
    public suspend fun clientStreaming(
        method: String,
        requests: Flow<ByteArray>,
        options: CallOptions = CallOptions(),
    ): ByteArray

    /**
     * Cold, like [serverStreaming]. [requests] is collected concurrently with the responses and
     * half-closes when it completes; when the server ends the call first, its collection is
     * canceled. A failure of [requests] cancels the call and fails the returned flow.
     */
    public fun bidiStreaming(
        method: String,
        requests: Flow<ByteArray>,
        options: CallOptions = CallOptions(),
    ): Flow<ByteArray>

    override fun close()
}
