//! Conversions between Java values and `kurpc-core` types.

use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig};
use base64::engine::{DecodePaddingMode, general_purpose};
use bytes::Bytes;
use jni::objects::{JByteArray, JObject, JObjectArray, JString};
use jni::strings::JNIStr;
use jni::{Env, jni_sig, jni_str};
use kurpc_core::{ChannelConfig, KeepAlive, Metadata, TransportConfig};

use crate::error::{BridgeError, Result};

/// `-bin` values travel as base64 so metadata stays a flat `String[]`. Padding is optional on
/// input, as in the gRPC wire format.
const BASE64_IN: GeneralPurpose = GeneralPurpose::new(
    &base64::alphabet::STANDARD,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);
const BASE64_OUT: GeneralPurpose = general_purpose::STANDARD;

pub(crate) fn string(env: &Env, value: &JString) -> Result<String> {
    if value.is_null() {
        return Err(BridgeError::InvalidArgument(
            "unexpected null string".to_owned(),
        ));
    }
    Ok(value.try_to_string(env)?)
}

pub(crate) fn bytes(env: &Env, value: &JByteArray) -> Result<Bytes> {
    Ok(Bytes::from(env.convert_byte_array(value)?))
}

/// Reads `[k0, v0, k1, v1, ...]`.
pub(crate) fn metadata(env: &mut Env, array: &JObjectArray<JString>) -> Result<Metadata> {
    let length = array.len(env)?;
    if length % 2 != 0 {
        return Err(BridgeError::InvalidArgument(
            "metadata must hold key-value pairs".to_owned(),
        ));
    }
    let mut entries = Vec::with_capacity(length / 2);
    for index in (0..length).step_by(2) {
        let key = array.get_element(env, index)?;
        let key = string(env, &key)?;
        let value = array.get_element(env, index + 1)?;
        let value = string(env, &value)?;
        let value = if key.ends_with("-bin") {
            BASE64_IN.decode(&value).map_err(|e| {
                BridgeError::InvalidArgument(format!("metadata {key:?} is not base64: {e}"))
            })?
        } else {
            value.into_bytes()
        };
        entries.push((key, Bytes::from(value)));
    }
    Ok(Metadata(entries))
}

/// Writes `[k0, v0, k1, v1, ...]`, the inverse of [`metadata`].
pub(crate) fn metadata_to_java<'local>(
    env: &mut Env<'local>,
    metadata: &Metadata,
) -> Result<JObjectArray<'local, JString<'local>>> {
    let array = JObjectArray::<JString>::new(env, metadata.0.len() * 2, JString::null())?;
    for (index, (key, value)) in metadata.iter().enumerate() {
        let value = if key.ends_with("-bin") {
            BASE64_OUT.encode(value)
        } else {
            String::from_utf8_lossy(value).into_owned()
        };
        let key = JString::from_str(env, key)?;
        array.set_element(env, index * 2, &key)?;
        let value = JString::from_str(env, value)?;
        array.set_element(env, index * 2 + 1, &value)?;
    }
    Ok(array)
}

/// Values of `NativeChannelConfig.transport`.
const TRANSPORT_TCP: i32 = 0;
#[cfg(unix)]
const TRANSPORT_UNIX: i32 = 1;
#[cfg(windows)]
const TRANSPORT_WINDOWS_NAMED_PIPE: i32 = 2;
/// `Transport.Custom` and `Transport.FileDescriptor`; Kotlin's dialer answers either way.
const TRANSPORT_CUSTOM: i32 = 3;

/// Reads a `NativeChannelConfig`, a flat Kotlin class with `@JvmField` properties:
///
/// | Field                     | Type       | Meaning                                       |
/// |---------------------------|------------|-----------------------------------------------|
/// | `transport`               | `int`      | `0` TCP, `1` Unix, `2` Windows named pipe,     |
/// |                           |            | `3` custom (see `relay.rs`)                    |
/// | `address`                 | `String`   | TCP host or Unix socket path                   |
/// | `port`                    | `int`      | TCP port                                       |
/// | `authority`               | `String`   | `:authority`                                   |
/// | `secure`                  | `boolean`  | `:scheme` is `https` rather than `http`        |
/// | `metadata`                | `String[]` | See [`metadata`]                               |
/// | `connectTimeoutMs`        | `long`     |                                                |
/// | `keepAliveIntervalMs`     | `long`     | `0` disables keep-alive                        |
/// | `keepAliveTimeoutMs`      | `long`     |                                                |
/// | `keepAliveWhileIdle`      | `boolean`  |                                                |
/// | `userAgent`               | `String?`  |                                                |
pub(crate) fn channel_config(
    env: &mut Env,
    config: &JObject,
    dialer: &JObject,
) -> Result<ChannelConfig> {
    let address = string_field(env, config, jni_str!("address"))?;
    let transport = match int_field(env, config, jni_str!("transport"))? {
        TRANSPORT_TCP => {
            let port = int_field(env, config, jni_str!("port"))?;
            let port = u16::try_from(port)
                .map_err(|_| BridgeError::InvalidArgument(format!("port {port} out of range")))?;
            TransportConfig::Tcp {
                host: address,
                port,
            }
        }
        #[cfg(unix)]
        TRANSPORT_UNIX => TransportConfig::Unix { path: address },
        #[cfg(windows)]
        TRANSPORT_WINDOWS_NAMED_PIPE => TransportConfig::WindowsNamedPipe { name: address },
        TRANSPORT_CUSTOM => crate::relay::custom_transport(env, dialer)?,
        other => {
            return Err(BridgeError::InvalidArgument(format!(
                "transport {other} is not supported on this platform"
            )));
        }
    };

    let keep_alive_interval = millis_field(env, config, jni_str!("keepAliveIntervalMs"))?;
    let keep_alive = if keep_alive_interval.is_zero() {
        None
    } else {
        Some(KeepAlive {
            interval: keep_alive_interval,
            timeout: millis_field(env, config, jni_str!("keepAliveTimeoutMs"))?,
            while_idle: bool_field(env, config, jni_str!("keepAliveWhileIdle"))?,
        })
    };

    let metadata_array = env
        .get_field(
            config,
            jni_str!("metadata"),
            jni_sig!("[Ljava/lang/String;"),
        )?
        .l()?;
    let metadata_array = env.cast_local::<JObjectArray<JString>>(metadata_array)?;

    Ok(ChannelConfig {
        transport,
        authority: string_field(env, config, jni_str!("authority"))?,
        secure: bool_field(env, config, jni_str!("secure"))?,
        metadata: metadata(env, &metadata_array)?,
        connect_timeout: millis_field(env, config, jni_str!("connectTimeoutMs"))?,
        keep_alive,
        user_agent: nullable_string_field(env, config, jni_str!("userAgent"))?,
    })
}

/// A non-negative millisecond count; negative values mean "none" and come back as `None`.
pub(crate) fn optional_millis(millis: i64) -> Option<Duration> {
    u64::try_from(millis).ok().map(Duration::from_millis)
}

fn int_field(env: &mut Env, object: &JObject, name: &JNIStr) -> Result<i32> {
    Ok(env.get_field(object, name, jni_sig!("I"))?.i()?)
}

fn bool_field(env: &mut Env, object: &JObject, name: &JNIStr) -> Result<bool> {
    Ok(env.get_field(object, name, jni_sig!("Z"))?.z()?)
}

fn millis_field(env: &mut Env, object: &JObject, name: &JNIStr) -> Result<Duration> {
    let millis = env.get_field(object, name, jni_sig!("J"))?.j()?;
    optional_millis(millis).ok_or_else(|| {
        BridgeError::InvalidArgument(format!("{name} must not be negative, got {millis}"))
    })
}

fn nullable_string_field(env: &mut Env, object: &JObject, name: &JNIStr) -> Result<Option<String>> {
    let value = env
        .get_field(object, name, jni_sig!("Ljava/lang/String;"))?
        .l()?;
    if value.is_null() {
        return Ok(None);
    }
    let value = env.cast_local::<JString>(value)?;
    Ok(Some(value.try_to_string(env)?))
}

fn string_field(env: &mut Env, object: &JObject, name: &JNIStr) -> Result<String> {
    nullable_string_field(env, object, name)?
        .ok_or_else(|| BridgeError::InvalidArgument(format!("{name} must not be null")))
}
