# kurpc

Kotlin Multiplatform gRPC client backed by a native library built from
[grpc-rust](https://github.com/grpc/grpc-rust) (tonic), called over JNI.

kurpc moves raw message bytes. It does not generate stubs or serialize messages: you pass the
encoded request and receive the encoded response, so any protobuf runtime works (protobuf-java,
protobuf-javalite, Wire, kotlinx-serialization-protobuf, ...).

- Unary, server-streaming, client-streaming and bidirectional calls as `suspend` functions and
  `Flow`s. Cancelling the coroutine cancels the call.
- Deadlines, metadata (text and `-bin`), keep-alive, `wait-for-ready`.
- Transports: TCP, Unix domain sockets (including Linux abstract sockets), Windows named pipes, a
  file descriptor, or any byte stream you provide (TLS is provided this way).

## Platforms

| Target  | Requirement                                   | Native library                             |
|---------|-----------------------------------------------|--------------------------------------------|
| JVM     | Java 8+                                       | `kurpc-natives-jvm` jar, one per OS / arch |
| Android | `minSdk` 21                                   | Included in the AAR                        |

Desktop classifiers of `kurpc-natives-jvm`:

| Classifier        | Platform                         |
|-------------------|----------------------------------|
| `linux-x86_64`    | Linux x86-64, glibc 2.17+        |
| `linux-aarch64`   | Linux AArch64, glibc 2.17+       |
| `macos-x86_64`    | macOS Intel                      |
| `macos-aarch64`   | macOS Apple silicon              |
| `windows-x86_64`  | Windows x64                      |
| `windows-aarch64` | Windows on ARM                   |

The AAR contains `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.

## Installation

kurpc is published to Maven Central under the group `io.github.xchacha20-poly1305`. Make sure
`mavenCentral()` is in your repositories:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### Version catalog

Add to `gradle/libs.versions.toml`:

```toml
[versions]
kurpc = "0.1.0"

[libraries]
kurpc = { module = "io.github.xchacha20-poly1305:kurpc", version.ref = "kurpc" }
kurpc-natives-jvm = { module = "io.github.xchacha20-poly1305:kurpc-natives-jvm", version.ref = "kurpc" }
```

Kotlin Multiplatform project (JVM and Android targets):

```kotlin
// build.gradle.kts
kotlin {
    jvm()
    android { /* ... */ }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kurpc)
        }
        jvmMain.dependencies {
            // One entry per platform the JVM application runs on.
            implementation(project.dependencies.variantOf(libs.kurpc.natives.jvm) { classifier("linux-x86_64") })
            implementation(project.dependencies.variantOf(libs.kurpc.natives.jvm) { classifier("macos-aarch64") })
            implementation(project.dependencies.variantOf(libs.kurpc.natives.jvm) { classifier("windows-x86_64") })
        }
    }
}
```

JVM-only project:

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.kurpc)
    implementation(variantOf(libs.kurpc.natives.jvm) { classifier("linux-x86_64") })
}
```

Android-only project (the AAR already contains the native libraries):

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.kurpc)
}
```

### Without a version catalog

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.xchacha20-poly1305:kurpc:0.1.0")
    // JVM only:
    implementation("io.github.xchacha20-poly1305:kurpc-natives-jvm:0.1.0:linux-x86_64")
}
```

### Native library on the JVM

A natives jar holds the library as a classpath resource. On first use kurpc picks the resource
matching the running OS and architecture, extracts it under `java.io.tmpdir` and loads it, so
shipping several classifiers in one distribution is fine. The lookup order is:

1. `Kurpc.loadLibrary("/path/to/libkurpc.so")`, called before the first `Channel` is created.
2. The `kurpc.library.path` system property (`-Dkurpc.library.path=/path/to/libkurpc.so`).
3. The classpath resource from `kurpc-natives-jvm`.
4. `System.loadLibrary("kurpc")`, which searches `java.library.path`.

Use 1, 2 or 4 when you build the library yourself or cannot write to the temporary directory.

R8 / ProGuard keep rules ship with both the JVM jar and the AAR; no extra configuration is needed.

## Usage

All examples use the package `io.github.xchacha20_poly1305.kurpc`.

### Create a channel

```kotlin
import io.github.xchacha20_poly1305.kurpc.*

val channel = Channel(
    ChannelConfig(
        transport = Transport.Tcp("127.0.0.1", 50051),
        authority = "localhost",
    ),
)
```

A `Channel` reconnects after a dropped connection and is safe to share between coroutines. Close it when you are done; calls still running fail with `ChannelClosedException`.

```kotlin
channel.use {
    // calls
}
```

### Unary call

Method names are full gRPC paths: `/<package>.<Service>/<Method>`. Encode the request and decode
the response with your protobuf runtime:

```kotlin
val reply: ByteArray = channel.unary(
    method = "/helloworld.Greeter/SayHello",
    request = HelloRequest.newBuilder().setName("kurpc").build().toByteArray(),
)
println(HelloReply.parseFrom(reply).message)
```

`unaryCall` returns the response headers and trailers along with the message:

```kotlin
val response: UnaryResponse = channel.unaryCall("/helloworld.Greeter/SayHello", request)
val requestId = response.headers.text("x-request-id")
```

### Streaming calls

Server streaming and bidirectional streaming return a cold `Flow`: each collection starts a new
call, and cancelling the collection cancels the call.

```kotlin
// Server streaming
channel.serverStreaming("/example.Feed/Subscribe", request).collect { bytes ->
    println(Event.parseFrom(bytes))
}

// Client streaming: sends every element, half-closes, returns the single response.
val summary: ByteArray = channel.clientStreaming(
    "/example.Upload/Send",
    flowOf(chunk1, chunk2, chunk3),
)

// Bidirectional streaming: requests are sent while responses are collected.
channel.bidiStreaming("/example.Chat/Talk", outgoing).collect { bytes ->
    println(ChatMessage.parseFrom(bytes))
}
```

### Deadlines and metadata

```kotlin
import kotlin.time.Duration.Companion.seconds

val options = CallOptions(
    timeout = 5.seconds,
    metadata = Metadata.of("authorization" to "Bearer $token") +
        Metadata.ofBinary("trace-bin" to traceBytes),
    waitForReady = false, // fail fast with UNAVAILABLE instead of waiting for a connection
)
channel.unary("/helloworld.Greeter/SayHello", request, options)
```

Metadata set in `ChannelConfig.metadata` is sent with every call on the channel.

### Errors

| Exception                  | Cause                                                      |
|----------------------------|------------------------------------------------------------|
| `StatusException`          | The call ended with a non-OK gRPC status                   |
| `TransportException`       | The connection could not be established or broke           |
| `ChannelClosedException`   | The channel was closed before or during the call           |
| `IllegalArgumentException` | Invalid input, such as a malformed method name or metadata |

```kotlin
try {
    channel.unary("/helloworld.Greeter/SayHello", request)
} catch (e: StatusException) {
    when (e.code) {
        Status.Code.NOT_FOUND -> println("not found: ${e.description}")
        Status.Code.DEADLINE_EXCEEDED -> println("timed out")
        else -> throw e
    }
}
```

`StatusException` also carries the raw `details` bytes (`grpc-status-details-bin`) and the
`trailers`.

### Transports

```kotlin
// Unix domain socket. A path starting with "\u0000" is a Linux / Android abstract socket.
Transport.Unix("/run/app/grpc.sock")

// Windows named pipe.
Transport.WindowsNamedPipe("""\\.\pipe\app""")

// A connected socket file descriptor (Unix only); kurpc takes ownership of it.
Transport.FileDescriptor { connectAndReturnFd() }

// Any byte stream. The connector runs again for every reconnect.
Transport.Custom {
    val socket = Socket("example.com", 50051)
    Connection.of(socket.getInputStream(), socket.getOutputStream())
}
```

`Connection.of` has adapters for an `InputStream` / `OutputStream` pair and a blocking
`ByteChannel` (both platforms), and for an Android `LocalSocket`.

### TLS

kurpc does not encrypt on its own. For TLS, use a `Transport.Custom` whose connector returns
`Connection.tls(...)`, and set `secure = true` so the request uses the `https` scheme:

```kotlin
val channel = Channel(
    ChannelConfig(
        transport = Transport.Custom {
            Connection.tls(Socket("api.example.com", 443), serverName = "api.example.com")
        },
        authority = "api.example.com",
        secure = true,
    ),
)
```

`Connection.tls` verifies the certificate against the platform's trust store by default and
offers ALPN `h2`. Pass your own `SSLSocketFactory` for a custom trust store or client
certificates. ALPN needs JDK 8u252+ on the JVM and, on Android below API 29, the platform's
Conscrypt provider.

### Keep-alive, user agent, runtime threads

```kotlin
import kotlin.time.Duration.Companion.seconds

ChannelConfig(
    transport = Transport.Tcp("127.0.0.1", 50051),
    connectTimeout = 5.seconds,
    keepAlive = KeepAlive(interval = 30.seconds, timeout = 10.seconds, whileIdle = true),
    userAgent = "my-app/1.0",
)

// Worker threads of the shared native runtime. Call before creating the first Channel.
Kurpc.configure(workerThreads = 2)
```

## Building from source

Requirements: JDK 21, the Rust toolchain from `rust-toolchain.toml`, and the Android SDK with the
NDK version from `gradle/libs.versions.toml`.

```sh
./gradlew :kurpc:check        # JVM tests on JDK 21 and JDK 8, Android Lint
cargo test --workspace         # Rust tests
make publish_local             # publishToMavenLocal for the host platform
make native_all                # every natives jar and the AAR; needs cargo-zigbuild
```

## License

[MIT](./LICENSE)
