use std::any::Any;
use std::fmt;

use jni::Env;
use jni::errors::ErrorPolicy;
use jni::jni_str;
use jni::strings::JNIString;

/// Why a native method failed synchronously. Call outcomes never take this path; they go to the
/// call's callback.
#[derive(Debug)]
pub(crate) enum BridgeError {
    /// A JNI operation failed, usually leaving a Java exception pending.
    Jni(jni::errors::Error),
    /// Thrown as `IllegalArgumentException`.
    InvalidArgument(String),
    /// Thrown as `IllegalStateException`. The runtime was already started, for example.
    IllegalState(String),
}

pub(crate) type Result<T, E = BridgeError> = std::result::Result<T, E>;

impl From<jni::errors::Error> for BridgeError {
    fn from(error: jni::errors::Error) -> Self {
        BridgeError::Jni(error)
    }
}

impl From<kurpc_core::Error> for BridgeError {
    fn from(error: kurpc_core::Error) -> Self {
        BridgeError::InvalidArgument(error.to_string())
    }
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            BridgeError::Jni(error) => write!(f, "JNI error: {error}"),
            BridgeError::InvalidArgument(message) | BridgeError::IllegalState(message) => {
                f.write_str(message)
            }
        }
    }
}

impl std::error::Error for BridgeError {}

/// Turns a [`BridgeError`] or a panic into a Java exception and returns the default value.
/// A Java exception that is already pending wins over both.
pub(crate) struct ThrowJavaException;

impl<T: Default> ErrorPolicy<T, BridgeError> for ThrowJavaException {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut (),
        error: BridgeError,
    ) -> jni::errors::Result<T> {
        if !env.exception_check() {
            let class = match error {
                BridgeError::InvalidArgument(_) => jni_str!("java/lang/IllegalArgumentException"),
                BridgeError::Jni(_) | BridgeError::IllegalState(_) => {
                    jni_str!("java/lang/IllegalStateException")
                }
            };
            // `throw_new` reports the exception it raised as an error; that is the point here.
            let _ = env.throw_new(class, JNIString::from(error.to_string()));
        }
        Ok(T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut (),
        payload: Box<dyn Any + Send + 'static>,
    ) -> jni::errors::Result<T> {
        if !env.exception_check() {
            let message = panic_message(payload.as_ref());
            let _ = env.throw_new(
                jni_str!("java/lang/IllegalStateException"),
                JNIString::from(format!("kurpc native panic: {message}")),
            );
        }
        Ok(T::default())
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> &str {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        message
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message
    } else {
        "non-string panic payload"
    }
}
