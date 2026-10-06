//! `Transport.Custom` and `Transport.FileDescriptor` (plan §5.4).
//!
//! Each connection tonic needs becomes a dial request handle passed to `NativeDialer.dial`. Kotlin
//! answers it exactly once with `dialStream`, `dialFd` or `dialFail`, each of which consumes the
//! handle. `dialStream` returns a pipe handle that Kotlin pumps from `Dispatchers.IO` and releases
//! with `pipeRelease` once both directions have stopped.
//!
//! Unlike the call functions, `pipeRead` and `pipeWrite` block: they return when bytes move, which
//! carries back-pressure across JNI without a callback per chunk.

use std::sync::Arc;

use jni::objects::{JByteArray, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use kurpc_core::TransportConfig;
use kurpc_core::relay::{DialRequest, Pipe, RequestDialer};

use crate::bridge::{native, on_worker};
use crate::convert;
use crate::error::{BridgeError, Result};
use crate::handle;

/// A custom transport that asks `dialer` (a Kotlin `NativeDialer`) for every connection.
pub(crate) fn custom_transport(env: &mut Env, dialer: &JObject) -> Result<TransportConfig> {
    if dialer.is_null() {
        return Err(BridgeError::InvalidArgument(
            "a custom transport needs a dialer".to_owned(),
        ));
    }
    let dialer = env.new_global_ref(dialer)?;
    let dialer = RequestDialer::new(move |request: DialRequest| {
        let request = handle::into_handle(request, &handle::LIVE.dials);
        let delivered = on_worker(|env| {
            env.call_method(
                &dialer,
                jni_str!("dial"),
                jni_sig!("(J)V"),
                &[JValue::Long(request)],
            )?;
            Ok(())
        });
        if !delivered {
            // Kotlin never took the handle. Dropping the request fails this dial.
            // Safety: the handle was created above and has not been passed on.
            let _ = unsafe { handle::release::<DialRequest>(request, &handle::LIVE.dials) };
        }
    });
    Ok(TransportConfig::Custom(Arc::new(dialer)))
}

/// `fun dialStream(request: Long): Long`
///
/// Answers the dial with a pipe and consumes `request`. Returns `0` when the dial was abandoned
/// (connect timeout, channel closed); Kotlin then closes its connection.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_dialStream<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    request: jlong,
) -> jlong {
    native(env, |_| {
        // Safety: Kotlin passes a live dial request and never uses it again.
        let request = unsafe { handle::release::<DialRequest>(request, &handle::LIVE.dials) }?;
        Ok(request
            .stream()
            .map_or(0, |pipe| handle::into_handle(pipe, &handle::LIVE.pipes)))
    })
}

/// `fun dialFd(request: Long, fd: Int)`
///
/// Answers the dial with a connected socket and consumes `request`. kurpc owns `fd` from here
/// on, including when this throws.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_dialFd<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    request: jlong,
    fd: jint,
) {
    native(env, |_| {
        // Taken before anything can fail, so the fd is closed on every error path.
        #[cfg(unix)]
        let fd = {
            use std::os::fd::{FromRawFd, OwnedFd};
            // Safety: the `FdConnector` contract hands ownership of an open fd to kurpc.
            (fd >= 0).then(|| unsafe { OwnedFd::from_raw_fd(fd) })
        };
        // Safety: Kotlin passes a live dial request and never uses it again.
        let request = unsafe { handle::release::<DialRequest>(request, &handle::LIVE.dials) }?;
        #[cfg(unix)]
        match fd {
            Some(fd) => {
                request.fd(fd);
                Ok(())
            }
            None => {
                request.fail("connector returned a negative fd".to_owned());
                Err(BridgeError::InvalidArgument(
                    "fd must not be negative".to_owned(),
                ))
            }
        }
        #[cfg(not(unix))]
        {
            let _ = fd;
            request.fail("file descriptor transport is Unix only".to_owned());
            Err(BridgeError::InvalidArgument(
                "file descriptor transport is Unix only".to_owned(),
            ))
        }
    })
}

/// `fun dialFail(request: Long, message: String)`
///
/// Fails the dial with `message` (calls see a `TransportException`) and consumes `request`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_dialFail<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    request: jlong,
    message: JString<'local>,
) {
    native(env, |env| {
        // Safety: Kotlin passes a live dial request and never uses it again.
        let request = unsafe { handle::release::<DialRequest>(request, &handle::LIVE.dials) }?;
        // Consume the request before converting, so a bad message still answers the dial.
        let message = convert::string(env, &message);
        request.fail(message.as_deref().unwrap_or("dial failed").to_owned());
        message.map(drop)
    })
}

/// `fun pipeRead(pipe: Long, buffer: ByteArray, offset: Int, length: Int): Int`
///
/// Blocks until tonic has written bytes, copies at most `length` of them into `buffer` at
/// `offset`, and returns the count. `-1` is end of stream: tonic dropped the connection.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_pipeRead<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    pipe: jlong,
    buffer: JByteArray<'local>,
    offset: jint,
    length: jint,
) -> jint {
    native(env, |env| {
        // Safety: Kotlin passes a live pipe handle.
        let pipe = unsafe { handle::borrow::<Pipe>(pipe) }?;
        let max = usize::try_from(length).map_err(|_| {
            BridgeError::InvalidArgument(format!("length must not be negative, got {length}"))
        })?;
        let read = pipe.read_blocking(max, |bytes| {
            buffer.set_region(env, offset, as_jbytes(bytes))?;
            Ok::<_, BridgeError>(bytes.len() as jint)
        });
        read.unwrap_or(Ok(-1))
    })
}

/// `fun pipeWrite(pipe: Long, buffer: ByteArray, offset: Int, length: Int): Boolean`
///
/// Blocks until tonic's side has room for all `length` bytes. `false` once tonic dropped the
/// connection.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_pipeWrite<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    pipe: jlong,
    buffer: JByteArray<'local>,
    offset: jint,
    length: jint,
) -> jboolean {
    native(env, |env| {
        // Safety: Kotlin passes a live pipe handle.
        let pipe = unsafe { handle::borrow::<Pipe>(pipe) }?;
        let length = usize::try_from(length).map_err(|_| {
            BridgeError::InvalidArgument(format!("length must not be negative, got {length}"))
        })?;
        let mut bytes = vec![0; length];
        buffer.get_region(env, offset, &mut bytes)?;
        Ok(pipe.write_blocking(as_u8s(&bytes)).is_ok())
    })
}

/// `fun pipeShutdown(pipe: Long)`
///
/// End of stream toward tonic: the Kotlin connection reached EOF or failed. tonic then drops
/// the connection, and a blocked `pipeRead` returns `-1`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_pipeShutdown<
    'local,
>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    pipe: jlong,
) {
    native(env, |_| {
        // Safety: Kotlin passes a live pipe handle.
        unsafe { handle::borrow::<Pipe>(pipe) }?.shutdown_blocking();
        Ok(())
    })
}

/// `fun pipeRelease(pipe: Long)`: after both pump directions have returned.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_pipeRelease<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    pipe: jlong,
) {
    native(env, |_| {
        // Safety: Kotlin passes a live pipe handle and never uses it again.
        unsafe { handle::release::<Pipe>(pipe, &handle::LIVE.pipes) }?;
        Ok(())
    })
}

fn as_jbytes(bytes: &[u8]) -> &[i8] {
    // Safety: u8 and i8 have the same size and alignment, and every bit pattern is valid.
    unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast(), bytes.len()) }
}

fn as_u8s(bytes: &[i8]) -> &[u8] {
    // Safety: as in `as_jbytes`.
    unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast(), bytes.len()) }
}
