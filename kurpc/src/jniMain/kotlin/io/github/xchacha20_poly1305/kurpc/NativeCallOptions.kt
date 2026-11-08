package io.github.xchacha20_poly1305.kurpc

import kotlin.time.Duration

/** Native connection readiness can be overridden per call without exposing it on browsers. */
public class NativeCallOptions(
    timeout: Duration? = null,
    metadata: Metadata = Metadata.Empty,
    public val waitForReady: Boolean = true,
) : CallOptions(timeout, metadata)
