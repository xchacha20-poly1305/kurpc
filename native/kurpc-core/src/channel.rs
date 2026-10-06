use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Bytes;
use http::Uri;
use http::uri::{Authority, PathAndQuery};
use tokio::sync::watch;
use tonic::client::Grpc;
use tonic::metadata::MetadataMap;
use tonic::transport::Endpoint;

use crate::call::{CallHandle, CallOptions, RequestQueue, Response, StreamSink};
use crate::codec::{PassthroughCodec, RequestStreamCodec};
use crate::error::is_connect_failure;
use crate::transport::{Connector, TransportConfig};
use crate::{Code, Error, Metadata, Result, runtime};

/// First delay before a wait-for-ready retry. Each later delay doubles, up to
/// [`READY_BACKOFF_CAP`]. The sleep sits inside the supervised call, so a deadline,
/// cancellation, or close ends it.
const READY_BACKOFF_START: Duration = Duration::from_millis(100);
const READY_BACKOFF_CAP: Duration = Duration::from_secs(2);

/// What a call sends. Unary and server-streaming calls have one message, cloned for each
/// wait-for-ready attempt; client-streaming and bidi calls read a [`RequestQueue`].
enum RequestBody {
    Single(Bytes),
    Stream(RequestQueue),
}

impl RequestBody {
    /// The call is over. A request stream still open is reset rather than left half open until
    /// the caller drops its handle.
    fn end(&self) {
        if let RequestBody::Stream(requests) = self {
            requests.abort();
        }
    }
}

#[derive(Debug, Clone)]
pub struct ChannelConfig {
    pub transport: TransportConfig,
    /// The `:authority` of every request.
    pub authority: String,
    /// Sends `:scheme` `https` instead of `http`. kurpc itself always speaks HTTP/2 in the
    /// clear; a channel is encrypted only when its transport runs TLS (plan §3.3).
    pub secure: bool,
    /// Sent with every call, before the call's own metadata.
    pub metadata: Metadata,
    pub connect_timeout: Duration,
    pub keep_alive: Option<KeepAlive>,
    pub user_agent: Option<String>,
}

impl ChannelConfig {
    pub fn new(transport: TransportConfig) -> Self {
        ChannelConfig {
            transport,
            authority: "localhost".to_owned(),
            secure: false,
            metadata: Metadata::default(),
            connect_timeout: Duration::from_secs(10),
            keep_alive: None,
            user_agent: None,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct KeepAlive {
    pub interval: Duration,
    pub timeout: Duration,
    pub while_idle: bool,
}

/// A lazily connected gRPC channel. Cloning shares the underlying connection.
#[derive(Clone)]
pub struct Channel {
    inner: Arc<Inner>,
}

struct Inner {
    grpc: tonic::transport::Channel,
    metadata: MetadataMap,
    closed: watch::Sender<bool>,
}

impl Channel {
    pub fn new(config: ChannelConfig) -> Result<Channel> {
        let endpoint = endpoint(&config)?;
        let mut metadata = MetadataMap::new();
        config.metadata.append_to(&mut metadata)?;
        // tonic spawns the channel's worker task, which needs the runtime in scope.
        let grpc = {
            let _guard = runtime::get().enter();
            endpoint.connect_with_connector_lazy(Connector::new(config.transport))
        };
        Ok(Channel {
            inner: Arc::new(Inner {
                grpc,
                metadata,
                closed: watch::Sender::new(false),
            }),
        })
    }

    /// Starts a unary call. `done` runs exactly once, on a runtime worker thread, and must not
    /// block.
    pub fn unary<F>(
        &self,
        method: &str,
        request: Bytes,
        options: CallOptions,
        done: F,
    ) -> CallHandle
    where
        F: FnOnce(Result<Response>) + Send + 'static,
    {
        let handle = CallHandle::new(0);
        self.start_single_response(&handle, method, RequestBody::Single(request), options, done);
        handle
    }

    /// Starts a client-streaming call. Requests go through [`CallHandle::send`] and end with
    /// [`CallHandle::close_send`]; `done` runs exactly once, as for [`unary`](Self::unary).
    pub fn client_streaming<F>(&self, method: &str, options: CallOptions, done: F) -> CallHandle
    where
        F: FnOnce(Result<Response>) + Send + 'static,
    {
        let (handle, requests) = CallHandle::with_requests(0);
        self.start_single_response(
            &handle,
            method,
            RequestBody::Stream(requests),
            options,
            done,
        );
        handle
    }

    /// Starts a server-streaming call.
    ///
    /// `sink` is invoked on a runtime worker. `on_headers` runs at most once, before any
    /// message, and only after the request has been sent. `on_complete` runs exactly once,
    /// including when the call is cancelled, hits its deadline, or the channel closes.
    pub fn server_streaming<S>(
        &self,
        method: &str,
        request: Bytes,
        options: CallOptions,
        sink: S,
    ) -> CallHandle
    where
        S: StreamSink,
    {
        let handle = CallHandle::new(options.initial_demand);
        self.start_stream_response(&handle, method, RequestBody::Single(request), options, sink);
        handle
    }

    /// Starts a bidi-streaming call: requests as for [`client_streaming`](Self::client_streaming),
    /// responses as for [`server_streaming`](Self::server_streaming). The two directions are
    /// independent; the server may end the call before the requests do.
    pub fn bidi_streaming<S>(&self, method: &str, options: CallOptions, sink: S) -> CallHandle
    where
        S: StreamSink,
    {
        let (handle, requests) = CallHandle::with_requests(options.initial_demand);
        self.start_stream_response(
            &handle,
            method,
            RequestBody::Stream(requests),
            options,
            sink,
        );
        handle
    }

    fn start_single_response<F>(
        &self,
        handle: &CallHandle,
        method: &str,
        body: RequestBody,
        options: CallOptions,
        done: F,
    ) where
        F: FnOnce(Result<Response>) + Send + 'static,
    {
        let call = handle.clone();
        let inner = Arc::clone(&self.inner);
        let method = method.to_owned();
        runtime::get().spawn(async move {
            let timeout = options.timeout;
            let result = inner
                .supervise(
                    &call,
                    timeout,
                    inner.single_response(&method, &body, &options),
                )
                .await;
            body.end();
            done(result);
        });
    }

    fn start_stream_response<S>(
        &self,
        handle: &CallHandle,
        method: &str,
        body: RequestBody,
        options: CallOptions,
        mut sink: S,
    ) where
        S: StreamSink,
    {
        let call = handle.clone();
        let inner = Arc::clone(&self.inner);
        let method = method.to_owned();
        runtime::get().spawn(async move {
            let timeout = options.timeout;
            let result = inner
                .supervise(
                    &call,
                    timeout,
                    inner.stream_response(&call, &method, &body, &options, &mut sink),
                )
                .await;
            body.end();
            // Outside `supervise`: cancel, deadline, and close drop the call future, and the
            // terminal callback still has to run once.
            sink.on_complete(result);
        });
    }

    /// Fails every running and future call with [`Error::Closed`]. Idempotent.
    pub fn close(&self) {
        self.inner.closed.send_replace(true);
    }
}

impl Inner {
    /// Runs `call` until it finishes, is cancelled, exceeds `timeout`, or the channel closes.
    /// Dropping the call future resets its HTTP/2 stream.
    async fn supervise<T>(
        &self,
        handle: &CallHandle,
        timeout: Option<Duration>,
        call: impl Future<Output = Result<T>>,
    ) -> Result<T> {
        let mut closed = self.closed.subscribe();
        let deadline = async {
            match timeout {
                Some(timeout) => tokio::time::sleep(timeout).await,
                None => std::future::pending().await,
            }
        };
        tokio::select! {
            biased;
            _ = closed.wait_for(|closed| *closed) => Err(Error::Closed),
            _ = handle.cancelled() => Err(Error::status(Code::Cancelled, "call cancelled")),
            _ = deadline => Err(Error::status(Code::DeadlineExceeded, "deadline exceeded")),
            result = call => result,
        }
    }

    async fn single_response(
        &self,
        method: &str,
        body: &RequestBody,
        options: &CallOptions,
    ) -> Result<Response> {
        let (headers, mut stream) = self.open(method, body, options).await?;
        let message = stream
            .message()
            .await?
            .ok_or_else(|| Error::status(Code::Internal, "missing response message"))?;
        let trailers = stream.trailers().await?.unwrap_or_default();
        Ok(Response {
            message,
            headers,
            trailers: Metadata::from(&trailers),
        })
    }

    async fn stream_response(
        &self,
        handle: &CallHandle,
        method: &str,
        body: &RequestBody,
        options: &CallOptions,
        sink: &mut dyn StreamSink,
    ) -> Result<Metadata> {
        let (headers, mut stream) = self.open(method, body, options).await?;
        // Headers mean the request was sent. A connect retry never reaches here, so
        // `on_headers` runs at most once.
        sink.on_headers(headers);
        while let Some(message) = stream.message().await? {
            // The message is already in hand. Waiting here holds that one message and does
            // not pull another, so HTTP/2 flow control stalls the server.
            handle.acquire().await;
            sink.on_message(message);
        }
        let trailers = stream.trailers().await?.unwrap_or_default();
        Ok(Metadata::from(&trailers))
    }

    /// Dials until response headers arrive.
    ///
    /// Only this function retries. A connect failure ([`is_connect_failure`]) is retried
    /// when `wait_for_ready` is set; the sleep sits inside the supervised call, so a
    /// deadline, cancellation, or close ends the wait. `grpc-timeout` on each attempt is
    /// the time still left. Anything after headers, including an HTTP/2 handshake error,
    /// is returned as-is: the request was already sent.
    async fn open(
        &self,
        method: &str,
        body: &RequestBody,
        options: &CallOptions,
    ) -> Result<(Metadata, tonic::Streaming<Bytes>)> {
        let started = Instant::now();
        let mut backoff = READY_BACKOFF_START;
        loop {
            let timeout = remaining(options.timeout, started)?;
            let mut grpc = Grpc::new(self.grpc.clone());
            // Lazy channels report a connect failure from `call`, not from `ready`.
            // A non-lazy failure here is the same class of error.
            let (connect_failure, error) = match grpc.ready().await {
                Err(error) => (is_connect_failure(&error), Error::from(error)),
                Ok(()) => match self.call(&mut grpc, method, body, options, timeout).await? {
                    Ok(response) => {
                        let (headers, stream, _) = response.into_parts();
                        return Ok((Metadata::from(&headers), stream));
                    }
                    // Classified on the tonic error: `Error::Transport` only keeps a string.
                    Err(status) => (is_connect_failure(&status), Error::from(status)),
                },
            };
            if !(connect_failure && options.wait_for_ready) {
                return Err(error);
            }
            // Inside the supervised call, so a deadline, cancel, or close ends the wait.
            tokio::time::sleep(backoff).await;
            backoff = (backoff * 2).min(READY_BACKOFF_CAP);
        }
    }

    /// One attempt. The outer `Result` is a request that could not be built; the inner one is
    /// tonic's outcome, which [`open`](Self::open) classifies.
    async fn call(
        &self,
        grpc: &mut Grpc<tonic::transport::Channel>,
        method: &str,
        body: &RequestBody,
        options: &CallOptions,
        timeout: Option<Duration>,
    ) -> Result<std::result::Result<tonic::Response<tonic::Streaming<Bytes>>, tonic::Status>> {
        Ok(match body {
            RequestBody::Single(message) => {
                let (path, request) =
                    self.request(method, message.clone(), &options.metadata, timeout)?;
                grpc.server_streaming(request, path, PassthroughCodec).await
            }
            RequestBody::Stream(requests) => {
                let (path, request) =
                    self.request(method, requests.clone(), &options.metadata, timeout)?;
                grpc.streaming(request, path, RequestStreamCodec).await
            }
        })
    }

    fn request<M>(
        &self,
        method: &str,
        message: M,
        metadata: &Metadata,
        timeout: Option<Duration>,
    ) -> Result<(PathAndQuery, tonic::Request<M>)> {
        if !method.starts_with('/') {
            return Err(Error::invalid_argument(format!(
                "method {method:?} must look like /package.Service/Method"
            )));
        }
        let path = PathAndQuery::try_from(method)
            .map_err(|e| Error::invalid_argument(format!("method {method:?}: {e}")))?;
        let mut request = tonic::Request::new(message);
        let outgoing = request.metadata_mut();
        *outgoing = self.metadata.clone();
        metadata.append_to(outgoing)?;
        if let Some(timeout) = timeout {
            request.set_timeout(timeout);
        }
        Ok((path, request))
    }
}

/// `grpc-timeout` for this attempt: whatever is left of the caller's deadline.
/// `Err` when the deadline has already passed, so no further request is sent.
fn remaining(timeout: Option<Duration>, started: Instant) -> Result<Option<Duration>> {
    let Some(timeout) = timeout else {
        return Ok(None);
    };
    let elapsed = started.elapsed();
    if elapsed >= timeout {
        Err(Error::status(Code::DeadlineExceeded, "deadline exceeded"))
    } else {
        Ok(Some(timeout - elapsed))
    }
}

fn endpoint(config: &ChannelConfig) -> Result<Endpoint> {
    let authority = Authority::try_from(config.authority.as_str())
        .map_err(|e| Error::invalid_argument(format!("authority {:?}: {e}", config.authority)))?;
    // The connector decides where to dial; tonic's connector wrapper only reads the URI's
    // scheme. It stays `http`, because a tonic built with a TLS feature (which feature
    // unification can enable from elsewhere in the build) refuses to dial an `https` URI
    // without its own TLS config. `:scheme` comes from the origin instead.
    let mut endpoint = Endpoint::from_shared(format!("http://{authority}"))
        .map_err(|e| Error::invalid_argument(format!("authority {authority}: {e}")))?
        .connect_timeout(config.connect_timeout);
    if config.secure {
        let origin = Uri::builder()
            .scheme("https")
            .authority(authority)
            .path_and_query("/")
            .build()
            .map_err(|e| Error::invalid_argument(format!("origin: {e}")))?;
        endpoint = endpoint.origin(origin);
    }
    if let Some(keep_alive) = config.keep_alive {
        endpoint = endpoint
            .http2_keep_alive_interval(keep_alive.interval)
            .keep_alive_timeout(keep_alive.timeout)
            .keep_alive_while_idle(keep_alive.while_idle);
    }
    if let Some(user_agent) = &config.user_agent {
        endpoint = endpoint
            .user_agent(user_agent.as_str())
            .map_err(|e| Error::invalid_argument(format!("user agent {user_agent:?}: {e}")))?;
    }
    Ok(endpoint)
}
