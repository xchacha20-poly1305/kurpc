use std::error::Error as StdError;
use std::fmt;

use bytes::Bytes;
use tonic::{Code, Status};

use crate::Metadata;

pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, Clone)]
pub enum Error {
    /// The server, or tonic on its behalf, ended the call with a non-OK status.
    Status {
        code: Code,
        message: String,
        details: Bytes,
        metadata: Metadata,
    },
    /// The connection could not be established or broke before a status arrived.
    Transport { message: String },
    /// The channel was closed before or during the call.
    Closed,
    /// The caller passed something that cannot be sent, such as a malformed method path.
    InvalidArgument { message: String },
}

impl Error {
    pub(crate) fn status(code: Code, message: impl Into<String>) -> Self {
        Error::Status {
            code,
            message: message.into(),
            details: Bytes::new(),
            metadata: Metadata::default(),
        }
    }

    pub(crate) fn invalid_argument(message: impl Into<String>) -> Self {
        Error::InvalidArgument {
            message: message.into(),
        }
    }
}

impl From<Status> for Error {
    fn from(status: Status) -> Self {
        // A status tonic synthesized from a transport failure has the underlying
        // error as its source. A status the server actually sent does not.
        // Connect failures (a custom dialer's TLS handshake among them) and a peer that
        // vanished without a grpc-status all land here; `Unavailable` from the server stays a status.
        if let Some(source) = transport_source(&status) {
            return Error::Transport {
                message: error_chain(source),
            };
        }
        Error::Status {
            code: status.code(),
            message: status.message().to_owned(),
            details: Bytes::copy_from_slice(status.details()),
            metadata: Metadata::from(status.metadata()),
        }
    }
}

impl From<tonic::transport::Error> for Error {
    fn from(error: tonic::transport::Error) -> Self {
        Error::Transport {
            message: error_chain(&error),
        }
    }
}

fn transport_source(status: &Status) -> Option<&(dyn StdError + 'static)> {
    let mut source = status.source();
    while let Some(error) = source {
        // `tonic::transport::Error` covers the channel (connect, HTTP/2 handshake).
        // A body that fails later is a bare `hyper::Error` with no grpc-status; that is
        // the peer going away. Either one means the server did not send a status.
        if error.is::<tonic::transport::Error>() || error.is::<hyper::Error>() {
            return Some(error);
        }
        source = error.source();
    }
    None
}

/// Whether `error` happened while connecting, before any request bytes were sent.
///
/// Checked on the tonic [`Status`] before it is converted to [`Error`], because
/// [`Error::Transport`] only keeps a string. tonic 0.14.5 builds the chain as follows:
///
/// - `Endpoint::connect_with_connector_lazy` wraps the connector in
///   `service::Connector`, which maps every dial error to [`tonic::ConnectError`]. TLS
///   belongs to a custom transport's dial, so a bad certificate or server name is a dial
///   error and still a connect failure.
/// - With a connect timeout (kurpc always sets one), `hyper_timeout::TimeoutConnector`
///   wraps that connector. A timeout becomes an `io::ErrorKind::TimedOut` wrapping
///   `tokio::time::error::Elapsed`, and `ConnectError` is absent, because the timeout
///   drops the connector future. Nothing was written. The `Elapsed` is reached through
///   `io::Error::get_ref`: `io::Error::source` skips the wrapped error and returns its
///   source instead.
/// - `Reconnect` with `is_lazy: true` does not fail `poll_ready` on that error. It
///   stores it and `call()` returns it without sending the HTTP request. The next
///   `poll_ready` dials again.
/// - `Grpc` turns the resulting `tonic::transport::Error` into a `Status` via
///   `Status::from_error`, which maps `ConnectError` to `Unavailable` and attaches the
///   original error as the status's source.
///
/// An HTTP/2 handshake failure is different: `MakeSendRequestService` handshakes only
/// after the connector returns, so the error is `hyper::Error`. The request may already
/// have been handed to hyper. Those are not connect failures and must not be retried,
/// even when a timed-out IO error sits further down the chain. The first `hyper::Error`
/// on the walk therefore stops the search.
pub(crate) fn is_connect_failure(error: &(dyn StdError + 'static)) -> bool {
    let mut current = Some(error);
    while let Some(error) = current {
        if error.is::<hyper::Error>() {
            return false;
        }
        if error.is::<tonic::ConnectError>() {
            return true;
        }
        if let Some(io) = error.downcast_ref::<std::io::Error>()
            && io.kind() == std::io::ErrorKind::TimedOut
            && io
                .get_ref()
                .is_some_and(|inner| inner.is::<tokio::time::error::Elapsed>())
        {
            return true;
        }
        current = error.source();
    }
    false
}

/// Formats an error with all of its sources, since the outermost transport error alone
/// ("transport error") says nothing about the cause.
fn error_chain(error: &(dyn StdError + 'static)) -> String {
    let mut message = error.to_string();
    let mut source = error.source();
    while let Some(error) = source {
        // Wrappers such as tonic's `ConnectError` print their source and also return it from
        // `source()`; repeating it would only duplicate the text.
        let text = error.to_string();
        if !message.ends_with(&text) {
            message.push_str(": ");
            message.push_str(&text);
        }
        source = error.source();
    }
    message
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Status { code, message, .. } => write!(f, "status {code:?}: {message}"),
            Error::Transport { message } => write!(f, "transport error: {message}"),
            Error::Closed => f.write_str("channel closed"),
            Error::InvalidArgument { message } => write!(f, "invalid argument: {message}"),
        }
    }
}

impl StdError for Error {}
