# kurpc

Kotlin Multiplatform gRPC client backed by a Rust (tonic) native library over JNI.
`plan.md` is the design; read the sections relevant to your task before changing code, and
update it when the implementation deliberately departs from it.

## Layout

- `native/kurpc-core`: JNI-free Rust core. Channel, passthrough codec, transports, runtime.
- `native/kurpc-jni`: `cdylib` (`libkurpc`). Argument conversion and handles only.
- `native/kurpc-testserver`: tonic server with the same passthrough codec; library + binary.
- `kurpc/`: KMP module. `commonMain` → `jniMain` → `jvmMain` / `androidMain`; tests in `jvmTest`
  (testserver subprocess) and `androidDeviceTest` (testserver packaged in the test APK).
- `build-logic/`: convention plugin `kurpc.native` with `CargoBuildTask` (host and Android natives).

## Commands

- `cargo test --workspace`
- `cargo clippy --workspace --all-targets -- -D warnings`
- `cargo fmt --check`
- `./gradlew :kurpc:check`: jvmTest on JDK 21 and JDK 8, Android Lint (`NewApi`).
- `./gradlew :kurpc:assemble :kurpc:assembleAndroidTest`: AAR with four ABIs, device-test APK;
  `connectedAndroidDeviceTest` needs a device or emulator.
- `make check` runs what CI's check job runs; `make native_all` builds every natives jar and the AAR.
- Cross builds go through Gradle: `-Pkurpc.targets=host|all|<list>`, `-Pkurpc.zigbuild=<path>`
  (plan §6.3). Public Kotlin API changes need `./gradlew :kurpc:updateKotlinAbi`.

## Rust conventions (follow the existing code)

- `kurpc-core` never depends on `jni`. Logic goes in core; bindings only convert.
- Calls are started with a callback that runs exactly once on a runtime worker thread,
  including on cancel (`Cancelled`) and close (`Error::Closed`). Calls never block the caller.
  New call kinds reuse `Inner::supervise` for cancel / deadline / close.
- Errors: `kurpc_core::Error` for call outcomes; `BridgeError` in `kurpc-jni` for synchronous
  failures of a native method, turned into Java exceptions by `ThrowJavaException`.
- Every `extern "system"` function body goes through `bridge::native(env, |env| ...)`, which
  catches panics. Handles are created and freed only through `handle.rs`.
- JNI symbol names are written out by hand (`Java_io_github_xchacha20_1poly1305_kurpc_...`) with a
  doc comment giving the Kotlin signature.
- Comments say why, not what. No commented-out code, no TODO without a plan section reference.
- Tests: integration tests in `native/kurpc-core/tests/` against `kurpc-testserver` in-process.
  Name tests after the behavior (`closed_channel_rejects_calls`).

## Kotlin constraints

- JVM bytecode targets Java 8 (`-Xjdk-release=1.8`); `jniMain` must also run on Android API 21
  without desugaring: no `java.util.stream`, `Optional`, `java.time`, `java.util.function`,
  `java.util.Base64` (use `kotlin.io.encoding.Base64`).
- `commonMain` uses only Kotlin stdlib and kotlinx-coroutines types.
- `NativeBridge` is an `object`; its `external fun` members must not be `internal`.

## Commits

One commit per completed, tested step. Do not commit build outputs.
