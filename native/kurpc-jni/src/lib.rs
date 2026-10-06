//! JNI binding of kurpc. Every function here converts arguments, manages handles and forwards to
//! `kurpc-core`; no gRPC logic lives in this crate.
//!
//! The Kotlin counterpart is `io.github.xchacha20_poly1305.kurpc.NativeBridge` in `jniMain`.
//! Native method names follow JNI mangling, where `_` in the package becomes `_1`.

mod bridge;
mod convert;
mod error;
mod handle;
mod relay;
mod vm;
