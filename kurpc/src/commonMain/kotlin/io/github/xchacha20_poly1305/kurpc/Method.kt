package io.github.xchacha20_poly1305.kurpc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Turns messages of one type into the bytes a [CallInvoker] sends, and back. */
public interface MessageCodec<T> {
    public fun encode(message: T): ByteArray

    public fun decode(bytes: ByteArray): T
}

/**
 * One RPC of a service: its [path] (`/<package>.<Service>/<Method>`) and how its messages are
 * encoded. `protoc-gen-kurpc` generates one per method; the typed calls below take it.
 */
public class MethodDescriptor<Request, Response>(
    public val path: String,
    public val requestCodec: MessageCodec<Request>,
    public val responseCodec: MessageCodec<Response>,
) {
    /**
     * A response the codec rejects ends the call with [Status.Code.INTERNAL], as it does in
     * other gRPC clients: the server answered, but not with what the method promises.
     */
    internal fun decodeResponse(bytes: ByteArray): Response = try {
        responseCodec.decode(bytes)
    } catch (error: Exception) {
        throw StatusException(
            Status.Code.INTERNAL,
            "failed to decode the response of $path: $error",
            ByteArray(0),
            Metadata.Empty,
            error,
        )
    }

    override fun toString(): String = "MethodDescriptor($path)"
}

public suspend fun <Request, Response> CallInvoker.unary(
    method: MethodDescriptor<Request, Response>,
    request: Request,
    options: CallOptions = CallOptions(),
): Response = method.decodeResponse(
    unary(method.path, method.requestCodec.encode(request), options),
)

/** See [CallInvoker.serverStreaming]. */
public fun <Request, Response> CallInvoker.serverStreaming(
    method: MethodDescriptor<Request, Response>,
    request: Request,
    options: CallOptions = CallOptions(),
): Flow<Response> = serverStreaming(method.path, method.requestCodec.encode(request), options)
    .map(method::decodeResponse)

/** See [CallInvoker.clientStreaming]. */
public suspend fun <Request, Response> CallInvoker.clientStreaming(
    method: MethodDescriptor<Request, Response>,
    requests: Flow<Request>,
    options: CallOptions = CallOptions(),
): Response = method.decodeResponse(
    clientStreaming(method.path, requests.map(method.requestCodec::encode), options),
)

/** See [CallInvoker.bidiStreaming]. */
public fun <Request, Response> CallInvoker.bidiStreaming(
    method: MethodDescriptor<Request, Response>,
    requests: Flow<Request>,
    options: CallOptions = CallOptions(),
): Flow<Response> = bidiStreaming(method.path, requests.map(method.requestCodec::encode), options)
    .map(method::decodeResponse)
