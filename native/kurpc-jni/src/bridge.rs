//! Native methods of `io.github.xchacha20_poly1305.kurpc.NativeBridge`, a Kotlin `object`, so
//! each receives the object instance after the environment.
//!
//! No method blocks. A call starts and returns a handle at once; its outcome arrives on a runtime
//! worker thread through the callback object, exactly once.

use bytes::Bytes;
use jni::objects::{Global, JByteArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jlongArray};
use jni::{Env, EnvUnowned, JavaVM, jni_sig, jni_str};
use kurpc_core::{CallHandle, CallOptions, Channel, Error, Metadata, Response, StreamSink};

use crate::convert;
use crate::error::{BridgeError, Result, ThrowJavaException};
use crate::handle;

/// Values of `kind` in `CallFailureCallback.onFailure`.
const FAILURE_STATUS: jint = 0;
const FAILURE_TRANSPORT: jint = 1;
const FAILURE_CLOSED: jint = 2;
const FAILURE_INVALID_ARGUMENT: jint = 3;

/// `debugLiveHandles` returns this so a synchronous failure still type-checks as `long[]`.
struct LongArray(jlongArray);

impl Default for LongArray {
    fn default() -> Self {
        Self(std::ptr::null_mut())
    }
}

/// Runs a native method body, turning errors and panics into Java exceptions.
pub(crate) fn native<'local, T: Default>(
    mut env: EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> Result<T>,
) -> T {
    env.with_env(body).resolve::<ThrowJavaException>()
}

/// `fun channelCreate(config: NativeChannelConfig, dialer: NativeDialer?): Long`
///
/// `dialer` is required for `TRANSPORT_CUSTOM` and ignored otherwise; the channel keeps a global
/// reference to it until the channel is dropped.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_channelCreate<
    'local,
>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    config: JObject<'local>,
    dialer: JObject<'local>,
) -> jlong {
    native(env, |env| {
        let config = convert::channel_config(env, &config, &dialer)?;
        Ok(handle::into_handle(
            Channel::new(config)?,
            &handle::LIVE.channels,
        ))
    })
}

/// `fun channelClose(channel: Long)`: closes the channel, failing its running calls with
/// `CLOSED`, and releases the handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_channelClose<
    'local,
>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    channel: jlong,
) {
    native(env, |_| {
        // Safety: Kotlin passes a live channel handle and never uses it again.
        let channel = unsafe { handle::release::<Channel>(channel, &handle::LIVE.channels) }?;
        channel.close();
        Ok(())
    })
}

/// `fun configure(workerThreads: Int)`
///
/// Worker count for the process-wide runtime. The runtime starts on the first channel, and this
/// fails after that: the count cannot change once workers exist.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_configure<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    worker_threads: jint,
) {
    native(env, |_| {
        let threads = usize::try_from(worker_threads)
            .ok()
            .filter(|threads| *threads > 0);
        let Some(threads) = threads else {
            return Err(BridgeError::InvalidArgument(format!(
                "workerThreads must be positive, got {worker_threads}"
            )));
        };
        kurpc_core::runtime::configure(|config| config.worker_threads = threads).map_err(|_| {
            BridgeError::IllegalState(
                "Kurpc.configure must be called before the first channel".to_owned(),
            )
        })?;
        Ok(())
    })
}

/// `fun debugLiveHandles(): LongArray`
///
/// Handles Kotlin still owns: `[channels, calls, dial requests, pipes]`. All are zero once every
/// channel is closed and every call and custom connection has finished.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_debugLiveHandles<
    'local,
>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
) -> jlongArray {
    native(env, |env| {
        let values = handle::LIVE.snapshot().map(|count| count as jlong);
        let array = env.new_long_array(values.len())?;
        array.set_region(env, 0, &values)?;
        Ok(LongArray(array.into_raw() as jlongArray))
    })
    .0
}

/// `fun unaryStart(channel: Long, method: String, request: ByteArray?, metadata: Array<String>,
/// timeoutMs: Long, waitForReady: Boolean, callback: UnaryCallback): Long`
///
/// A `null` request starts a client-streaming call: requests go through `callSend` and end with
/// `callCloseSend`. `timeoutMs < 0` means no deadline. The returned call handle must be released with
/// `callRelease`, whether or not the callback has run.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_unaryStart<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    channel: jlong,
    method: JString<'local>,
    request: JByteArray<'local>,
    metadata: JObjectArray<'local, JString<'local>>,
    timeout_ms: jlong,
    wait_for_ready: jboolean,
    callback: JObject<'local>,
) -> jlong {
    native(env, |env| {
        // Safety: Kotlin passes a live channel handle.
        let channel = unsafe { handle::borrow::<Channel>(channel) }?;
        let method = convert::string(env, &method)?;
        let request = optional_request(env, &request)?;
        let options = call_options(env, &metadata, timeout_ms, wait_for_ready, 0)?;
        let callback = env.new_global_ref(&callback)?;
        let done = move |result| {
            on_worker(|env| deliver_unary(env, &callback, result));
        };
        let call = match request {
            Some(request) => channel.unary(&method, request, options, done),
            None => channel.client_streaming(&method, options, done),
        };
        Ok(handle::into_handle(call, &handle::LIVE.calls))
    })
}

/// `fun streamStart(channel: Long, method: String, request: ByteArray?, metadata: Array<String>,
/// timeoutMs: Long, waitForReady: Boolean, initialDemand: Int, callback: StreamCallback): Long`
///
/// A `null` request starts a bidi call, with requests as for `unaryStart`. `initialDemand` is
/// how many messages may be delivered before `callRequest`. The returned call
/// handle must be released with `callRelease`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_streamStart<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    channel: jlong,
    method: JString<'local>,
    request: JByteArray<'local>,
    metadata: JObjectArray<'local, JString<'local>>,
    timeout_ms: jlong,
    wait_for_ready: jboolean,
    initial_demand: jint,
    callback: JObject<'local>,
) -> jlong {
    native(env, |env| {
        // Safety: Kotlin passes a live channel handle.
        let channel = unsafe { handle::borrow::<Channel>(channel) }?;
        let method = convert::string(env, &method)?;
        let request = optional_request(env, &request)?;
        let initial_demand = u32::try_from(initial_demand).map_err(|_| {
            BridgeError::InvalidArgument(format!(
                "initialDemand must not be negative, got {initial_demand}"
            ))
        })?;
        let options = call_options(env, &metadata, timeout_ms, wait_for_ready, initial_demand)?;
        let callback = env.new_global_ref(&callback)?;
        let sink = JavaStreamSink { callback };
        let call = match request {
            Some(request) => channel.server_streaming(&method, request, options, sink),
            None => channel.bidi_streaming(&method, options, sink),
        };
        Ok(handle::into_handle(call, &handle::LIVE.calls))
    })
}

/// `fun callSend(call: Long, message: ByteArray, callback: SendCallback)`
///
/// Queues one request of a client-streaming or bidi call. `SendCallback.onSent(queued)` runs
/// once on a runtime worker: `false` when the call takes no more requests. Send the next message
/// only after `onSent`, which keeps them in order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_callSend<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    call: jlong,
    message: JByteArray<'local>,
    callback: JObject<'local>,
) {
    native(env, |env| {
        // Safety: Kotlin passes a live call handle.
        let call = unsafe { handle::borrow::<CallHandle>(call) }?;
        let message = convert::bytes(env, &message)?;
        let callback = env.new_global_ref(&callback)?;
        call.send(message, move |queued| {
            on_worker(|env| {
                env.call_method(
                    &callback,
                    jni_str!("onSent"),
                    jni_sig!("(Z)V"),
                    &[JValue::Bool(queued)],
                )?;
                Ok(())
            });
        });
        Ok(())
    })
}

/// `fun callCloseSend(call: Long)`: ends the request stream (half-close).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_callCloseSend<
    'local,
>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    call: jlong,
) {
    native(env, |_| {
        // Safety: Kotlin passes a live call handle.
        unsafe { handle::borrow::<CallHandle>(call) }?.close_send();
        Ok(())
    })
}

/// `fun callRequest(call: Long, n: Int)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_callRequest<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    call: jlong,
    n: jint,
) {
    native(env, |_| {
        let n = u32::try_from(n).map_err(|_| {
            BridgeError::InvalidArgument(format!("n must not be negative, got {n}"))
        })?;
        // Safety: Kotlin passes a live call handle.
        unsafe { handle::borrow::<CallHandle>(call) }?.request(n);
        Ok(())
    })
}

/// `fun callRelease(call: Long)`
///
/// Releasing means the caller no longer wants the outcome, so a call that is still running is
/// cancelled first. Its callback still runs once, with `CANCELLED`. Kotlin releases every call
/// exactly once, on every exit path, which makes this the only way to cancel.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_xchacha20_1poly1305_kurpc_NativeBridge_callRelease<'local>(
    env: EnvUnowned<'local>,
    _bridge: JObject<'local>,
    call: jlong,
) {
    native(env, |_| {
        // Safety: Kotlin passes a live call handle and never uses it again.
        unsafe { handle::release::<CallHandle>(call, &handle::LIVE.calls) }?.cancel();
        Ok(())
    })
}

/// `null` selects the streaming-request variant of a call.
fn optional_request(env: &Env, request: &JByteArray) -> Result<Option<Bytes>> {
    if request.is_null() {
        return Ok(None);
    }
    Ok(Some(convert::bytes(env, request)?))
}

fn call_options(
    env: &mut Env,
    metadata: &JObjectArray<JString>,
    timeout_ms: jlong,
    wait_for_ready: jboolean,
    initial_demand: u32,
) -> Result<CallOptions> {
    Ok(CallOptions {
        timeout: convert::optional_millis(timeout_ms),
        metadata: convert::metadata(env, metadata)?,
        wait_for_ready,
        initial_demand,
    })
}

/// Holds the stream callback for the life of the call. Dropped on the runtime worker, which is
/// already attached, so deleting the global reference does not attach a thread.
struct JavaStreamSink {
    callback: Global<JObject<'static>>,
}

impl StreamSink for JavaStreamSink {
    fn on_headers(&mut self, headers: Metadata) {
        let callback = &self.callback;
        on_worker(|env| {
            let headers = convert::metadata_to_java(env, &headers)?;
            env.call_method(
                callback,
                jni_str!("onHeaders"),
                jni_sig!("([Ljava/lang/String;)V"),
                &[JValue::Object(&headers)],
            )?;
            Ok(())
        });
    }

    fn on_message(&mut self, message: Bytes) {
        let callback = &self.callback;
        on_worker(|env| {
            let message = env.byte_array_from_slice(&message)?;
            env.call_method(
                callback,
                jni_str!("onMessage"),
                jni_sig!("([B)V"),
                &[JValue::Object(&message)],
            )?;
            Ok(())
        });
    }

    fn on_complete(&mut self, result: kurpc_core::Result<Metadata>) {
        let callback = &self.callback;
        on_worker(|env| match result {
            Ok(trailers) => {
                let trailers = convert::metadata_to_java(env, &trailers)?;
                env.call_method(
                    callback,
                    jni_str!("onComplete"),
                    jni_sig!("([Ljava/lang/String;)V"),
                    &[JValue::Object(&trailers)],
                )?;
                Ok(())
            }
            Err(error) => deliver_failure(env, callback, error),
        });
    }
}

/// Runs `deliver` on the current runtime worker, which is already attached to the JVM.
///
/// Callbacks are not expected to throw. If one does, there is no Java caller left to receive the
/// exception, so it is reported on stderr instead of vanishing. Returns whether `deliver`
/// completed, for a caller that has to reclaim what it was handing over.
pub(crate) fn on_worker(deliver: impl FnOnce(&mut Env) -> Result<()>) -> bool {
    let Ok(vm) = JavaVM::singleton() else {
        return false;
    };
    match vm.attach_current_thread(deliver) {
        Ok(()) => true,
        Err(error) => {
            eprintln!("kurpc: failed to call into Kotlin: {error}");
            false
        }
    }
}

/// `UnaryCallback.onSuccess(message: ByteArray, headers: Array<String>, trailers: Array<String>)`
/// or `UnaryCallback.onFailure(kind: Int, code: Int, message: String, details: ByteArray,
/// trailers: Array<String>)`.
fn deliver_unary(
    env: &mut Env,
    callback: &JObject,
    result: kurpc_core::Result<Response>,
) -> Result<()> {
    match result {
        Ok(response) => {
            let message = env.byte_array_from_slice(&response.message)?;
            let headers = convert::metadata_to_java(env, &response.headers)?;
            let trailers = convert::metadata_to_java(env, &response.trailers)?;
            env.call_method(
                callback,
                jni_str!("onSuccess"),
                jni_sig!("([B[Ljava/lang/String;[Ljava/lang/String;)V"),
                &[
                    JValue::Object(&message),
                    JValue::Object(&headers),
                    JValue::Object(&trailers),
                ],
            )?;
        }
        Err(error) => deliver_failure(env, callback, error)?,
    }
    Ok(())
}

/// `CallFailureCallback.onFailure(kind: Int, code: Int, message: String, details: ByteArray,
/// trailers: Array<String>)`, shared by unary and stream callbacks.
fn deliver_failure(env: &mut Env, callback: &JObject, error: Error) -> Result<()> {
    let none = || (Bytes::new(), Metadata::default());
    let (kind, code, message, (details, trailers)) = match error {
        Error::Status {
            code,
            message,
            details,
            metadata,
        } => (FAILURE_STATUS, code as jint, message, (details, metadata)),
        Error::Transport { message } => (FAILURE_TRANSPORT, 0, message, none()),
        Error::Closed => (FAILURE_CLOSED, 0, Error::Closed.to_string(), none()),
        Error::InvalidArgument { message } => (FAILURE_INVALID_ARGUMENT, 0, message, none()),
    };
    let message = JString::from_str(env, message)?;
    let details = env.byte_array_from_slice(&details)?;
    let trailers = convert::metadata_to_java(env, &trailers)?;
    env.call_method(
        callback,
        jni_str!("onFailure"),
        jni_sig!("(IILjava/lang/String;[B[Ljava/lang/String;)V"),
        &[
            JValue::Int(kind),
            JValue::Int(code),
            JValue::Object(&message),
            JValue::Object(&details),
            JValue::Object(&trailers),
        ],
    )?;
    Ok(())
}
