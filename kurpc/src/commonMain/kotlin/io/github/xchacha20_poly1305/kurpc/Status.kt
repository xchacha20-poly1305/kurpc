package io.github.xchacha20_poly1305.kurpc

public object Status {
    /** The 17 standard gRPC status codes, with the values tonic uses. */
    public enum class Code(public val value: Int) {
        OK(0),
        CANCELLED(1),
        UNKNOWN(2),
        INVALID_ARGUMENT(3),
        DEADLINE_EXCEEDED(4),
        NOT_FOUND(5),
        ALREADY_EXISTS(6),
        PERMISSION_DENIED(7),
        RESOURCE_EXHAUSTED(8),
        FAILED_PRECONDITION(9),
        ABORTED(10),
        OUT_OF_RANGE(11),
        UNIMPLEMENTED(12),
        INTERNAL(13),
        UNAVAILABLE(14),
        DATA_LOSS(15),
        UNAUTHENTICATED(16),
        ;

        public companion object {
            public fun fromValue(value: Int): Code {
                val found = entries.find { it.value == value }
                if (found == null) {
                    throw IllegalArgumentException("unknown status code $value")
                }
                return found
            }
        }
    }
}

public class StatusException(
    public val code: Status.Code,
    public val description: String,
    public val details: ByteArray,
    public val trailers: Metadata,
) : RuntimeException("status $code: $description")

public class TransportException(message: String) : RuntimeException(message)

public class ChannelClosedException(message: String = "channel closed") : RuntimeException(message)
