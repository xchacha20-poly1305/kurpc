# kurpc

Kotlin Multiplatform RPC client. JVM and Android use a native library built from
[grpc-rust](https://github.com/grpc/grpc-rust) (tonic) over JNI. Browser JS and Wasm use
gRPC-Web over `fetch`, and `grpc-websockets` for request streams and, when asked, server streams.

kurpc moves raw message bytes: you pass the encoded request and receive the encoded response,
so any protobuf runtime works (protobuf-java, protobuf-javalite, Wire,
kotlinx-serialization-protobuf, ...). For
[Wire](https://github.com/square/wire) messages, the `kurpc-wire` schema handler generates typed
clients ([Generated clients](#generated-clients)) in common Kotlin for all supported platforms.

- Unary, server-streaming, client-streaming and bidirectional calls as `suspend` functions and
  `Flow`s. Cancelling the coroutine cancels the call.
- Deadlines and metadata (text and `-bin`); native channels also support keep-alive and `wait-for-ready`.
- Transports: TCP, Unix domain sockets (including Linux abstract sockets), Windows named pipes, a
  file descriptor, or any byte stream you provide (TLS is provided this way).

I forget why I named it "kurpc". "u" may be "you", you can customize it. Or means Unix domain socket.

## Platforms

| Target  | Requirement                                   | Native library                             |
|---------|-----------------------------------------------|--------------------------------------------|
| JVM     | Java 8+                                       | `kurpc-natives-jvm` jar, one per OS / arch |
| Android | `minSdk` 21                                   | Included in the AAR                        |
| JS | Modern browser with Fetch streams and WebSocket | No native library |
| wasmJs | Modern browser with Wasm GC support | No native library |

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
kurpc = "0.3.0"

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
    implementation("io.github.xchacha20-poly1305:kurpc:0.3.0")
    // JVM only:
    implementation("io.github.xchacha20-poly1305:kurpc-natives-jvm:0.3.0:linux-x86_64")
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

### Browser channel (JS and wasmJs)

Declare `js { browser() }` and/or `wasmJs { browser() }` in the application and use the same
`io.github.xchacha20-poly1305:kurpc` dependency. No extra script tags, npm package installation,
or generated JavaScript message classes are needed. The bridge has no runtime dependencies and
is embedded in the published Kotlin libraries.

```kotlin
val channel = Channel(
    WebChannelConfig(
        baseUrl = "https://api.example.com",
        metadata = Metadata.of("authorization" to "Bearer $token"),
    ),
)
val response = channel.unary("/example.Service/Method", encodedRequest)
```

`baseUrl` can include a path prefix. Ordinary calls and server streams require a binary
gRPC-Web endpoint; **Connect protocol endpoints and native gRPC endpoints alone are not enough**.
Request streams use the same endpoint over `ws`/`wss`, with the `grpc-websockets` subprotocol
defined by improbable-eng/grpc-web. Set `webSocketBaseUrl` (an HTTP(S) URL) when that
endpoint is hosted separately. Each WebSocket RPC owns one WebSocket.

Server streams use `fetch` unless told otherwise:

```kotlin
// Every server stream of the channel.
Channel(WebChannelConfig(baseUrl, serverStreamingTransport = WebStreamTransport.WebSocket))

// One call, whatever the channel's default is.
channel.serverStreaming(method, request, WebCallOptions(serverStreamingTransport = WebStreamTransport.Fetch))
```

| | `Fetch` | `WebSocket` |
|---|---|---|
| Connections | Over HTTP/1.1, one of the browser's connections to the origin (typically six) per open stream; further calls wait | One WebSocket per stream, outside that limit |
| Slow collector | The response is read as it is collected, so the server slows down | Messages queue up to `maxQueuedBytes` / `maxQueuedMessages`, then the call fails |

Use `WebSocket` for many long-lived subscriptions to an HTTP/1.1 endpoint, `Fetch` for large
transfers and for HTTP/2 or HTTP/3 endpoints.

Browser channels support the same four call methods, `MessageCodec`, and `MethodDescriptor`.
Clients generated for Wire messages ([generated clients](#generated-clients)) run here; other callers
supply their own encoded messages or codecs. Streams are cold, and input streams are collected concurrently with responses.
Cancellation aborts the request; `close()` terminates pending calls and rejects new calls.
Deadlines cover the whole call. Interrupted calls are not replayed automatically.

WebSocket sends wait when the outgoing buffer is full. Incoming WebSocket messages are bounded by
`maxQueuedBytes` (16 MiB) and `maxQueuedMessages` (256); overflow ends the call with
`RESOURCE_EXHAUSTED`, without silently dropping messages. `maxMessageBytes` defaults to 4 MiB
and `sendBufferBytes` to 1 MiB. These limits cover kurpc's data structures, not all browser buffers.

HTTPS/WSS certificate verification, forbidden HTTP headers, and CORS follow browser rules.
Fetch may combine repeated text metadata and does not preserve wire order. Cross-origin response
headers must be exposed by the server. Binary metadata is decoded, including repeated `-bin`
values joined by Fetch. WebSocket metadata retains the entries delivered by its protocol.
Authentication uses caller-provided metadata; there is no cookie-policy or custom-fetch API.
Node.js, Deno and wasmWasi are outside the supported runtime targets.

### API migration

`Channel` is now a common interface. `Channel(ChannelConfig(...))` remains the JVM/Android
factory; browser code uses `Channel(WebChannelConfig(...))`. Create the channel in platform code
and pass it to shared application code. `ChannelConfig`, `Transport`, `Connection`, `KeepAlive`
and `Kurpc` are now native-only APIs.

Common `CallOptions` contains timeout and metadata. For a native per-call readiness setting,
replace `CallOptions(waitForReady = ...)` with `NativeCallOptions(waitForReady = ...)`.
`CallOptions` is now an extensible class rather than a data class; its generated `copy`,
destructuring and value-equality methods are no longer available. Consumers must recompile.

### Unary call

Method names are full gRPC paths: `/<package>.<Service>/<Method>`. Encode the request and decode
the response with your protobuf runtime:

```kotlin
val reply: ByteArray = channel.unary(
    method = "/helloworld.Greeter/SayHello",
    request = HelloRequest(name = "kurpc").encode(),
)
println(HelloReply.ADAPTER.decode(reply).message)
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
    println(Event.ADAPTER.decode(bytes))
}

// Client streaming: sends every element, half-closes, returns the single response.
val summary: ByteArray = channel.clientStreaming(
    "/example.Upload/Send",
    flowOf(chunk1, chunk2, chunk3),
)

// Bidirectional streaming: requests are sent while responses are collected.
channel.bidiStreaming("/example.Chat/Talk", outgoing).collect { bytes ->
    println(ChatMessage.ADAPTER.decode(bytes))
}
```

### Generated clients

`kurpc-wire` is a Wire custom schema handler. Wire generates Kotlin messages and kurpc
clients in the same build, for JVM, Android, JS and Wasm:

Before the first release of the handler, run `./gradlew :kurpc-wire:publishToMavenLocal`
from this checkout. The example below resolves that local snapshot.

```kotlin
buildscript {
    repositories { mavenLocal(); mavenCentral() }
    dependencies {
        classpath("io.github.xchacha20-poly1305:kurpc-wire:0.3.1-SNAPSHOT")
    }
}

plugins {
    id("com.squareup.wire") version "7.2.1"
}

// In a KMP module, put these dependencies in commonMain.
dependencies {
    implementation(libs.kurpc)
    implementation("com.squareup.wire:wire-runtime:7.2.1")
}

wire {
    kotlin { rpcRole = "none" }
    custom {
        schemaHandlerFactoryClass.set("io.github.xchacha20_poly1305.kurpc.wire.KurpcSchemaHandlerFactory")
    }
}
```

For each service, the handler generates `<Service>Client(invoker)` and `<Service>Kurpc`.
The client exposes suspend functions for unary and client-streaming calls, and Flow for
server-streaming and bidirectional calls. Every method accepts optional `CallOptions`.
The descriptor object exposes `SERVICE_NAME` and a `MethodDescriptor` per method, usable
with kurpc's typed `unary`, `serverStreaming`, `clientStreaming` and `bidiStreaming` calls.

```kotlin
val greeter = GreeterClient(channel)
val reply = greeter.sayHello(HelloRequest(name = "kurpc"), CallOptions(timeout = 5.seconds))
```

The generated package and message names follow Wire's Kotlin generator. Use its default
immutable message configuration without `nameSuffix`, `javaInterop` or `mutableTypes`.
Well-known types use Wire's representations: Empty is Unit, wrappers are nullable Kotlin
values, Timestamp is Wire Instant, and Any is AnyMessage. Empty requests default to Unit.
RPC paths preserve the proto package and method names. Comments and deprecations are retained;
conflicting generated class names and Kotlin method names fail generation.

`Channel` implements `CallInvoker`; tests can supply a fake invoker. Invalid response bytes
fail with an INTERNAL `StatusException` whose cause is the decoding exception.

For Wire's command-line compiler, include `kurpc-wire` and its dependencies on the JVM
classpath and pass `--kotlin_rpc_role=none`, `--custom_out=<directory>` and
`--schema_handler_factory_class=io.github.xchacha20_poly1305.kurpc.wire.KurpcSchemaHandlerFactory`.
The old protoc plugin and protobuf-java generator backend have been removed.

### Deadlines and metadata

```kotlin
import kotlin.time.Duration.Companion.seconds

val options = CallOptions(
    timeout = 5.seconds,
    metadata = Metadata.of("authorization" to "Bearer $token") +
        Metadata.ofBinary("trace-bin" to traceBytes),
)
channel.unary("/helloworld.Greeter/SayHello", request, options)
```

Metadata set in `ChannelConfig.metadata` or `WebChannelConfig.metadata` is sent with every call.
JVM/Android callers can use `NativeCallOptions(waitForReady = false)` to fail fast instead of
waiting for a connection.

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

### Native transports

```kotlin
// Unix domain socket, also supported on Windows 10 1803+.
// A path starting with "\u0000" is a Linux / Android abstract socket.
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

### Native TLS

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

Requirements: JDK 21, Node.js 24/npm, the Rust toolchain from `rust-toolchain.toml`, and the
Android SDK with the NDK version from `gradle/libs.versions.toml`.

```sh
./gradlew :kurpc:check        # JVM tests, Android Lint, ABI, web bridge and web channel tests on Node
./gradlew :kurpc-wire:check  # Wire schema handler and generated-client integration tests
cargo test --workspace         # Rust tests
make publish_local             # publishToMavenLocal for the host platform
make native_all                # every natives jar and the AAR; needs cargo-zigbuild
```

The browser bridge is built and packaged by Gradle. See
[web bridge maintenance](web-bridge/README.md).

## License

[MIT](./LICENSE). The browser artifacts embed Babel runtime helpers (MIT); their license is
included with them.
