//! JNI-free gRPC client core of kurpc.
//!
//! Messages cross this API as opaque [`Bytes`](bytes::Bytes): the caller serializes requests and
//! parses responses itself. Calls are started with a completion callback rather than as `async fn`
//! so that every binding layer (JNI now, a C ABI later) only converts arguments and never schedules
//! tasks on its own.

mod call;
mod channel;
mod codec;
mod error;
mod metadata;
pub mod relay;
pub mod runtime;
mod transport;

pub use call::{CallHandle, CallOptions, Response, StreamSink};
pub use channel::{Channel, ChannelConfig, KeepAlive};
pub use codec::PassthroughCodec;
pub use error::{Error, Result};
pub use metadata::Metadata;
pub use tonic::Code;
pub use transport::{CustomDialer, Dialed, TransportConfig};
