//! A gRPC server for kurpc's tests. It uses the same passthrough codec as the client and
//! dispatches on the method path, so it needs no protobuf definitions.
//!
//! | Method                      | Behavior                                                        |
//! |-----------------------------|-----------------------------------------------------------------|
//! | `/kurpc.test.Test/Echo`     | Returns the request body; echoes request metadata as headers    |
//! | `/kurpc.test.Test/Fail`     | Body `<code>:<message>`; ends with that status                  |
//! | `/kurpc.test.Test/Sleep`    | Sleeps for the body's decimal milliseconds, then returns it     |
//! | `/kurpc.test.Test/Count`    | Server stream. `<n>` sends n messages; `<n>:<k>` errors after k |
//! | `/kurpc.test.Test/Infinite` | Server stream until the client cancels                          |
//! | `/kurpc.test.Test/Collect`  | Client stream; returns `<count>:<bytes>`. A `fail` message ends |
//! |                             | the call at once with `InvalidArgument`                         |
//! | `/kurpc.test.Test/Chat`     | Bidi; echoes each message. `bye` ends the call OK without       |
//! |                             | reading further                                                 |
//! | `/kurpc.test.Test/Stats`    | Unary text snapshot of the counters below                       |
//! | `/kurpc.test.Test/Scheme`   | Returns the request's `:scheme`                                 |

use std::convert::Infallible;
use std::net::IpAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use bytes::Bytes;
use kurpc_core::PassthroughCodec;
use tokio::net::TcpListener;
#[cfg(unix)]
use tokio::net::UnixListener;
use tokio::sync::watch;
use tokio_stream::Stream;
use tokio_stream::wrappers::TcpListenerStream;
#[cfg(unix)]
use tokio_stream::wrappers::UnixListenerStream;
use tonic::body::Body;
use tonic::server::{Grpc, NamedService};
use tonic::transport::{Identity, Server, ServerTlsConfig};
use tonic::{Code, Request, Response, Status, Streaming};
use tower::{Service, service_fn};

pub const SERVICE: &str = "kurpc.test.Test";

/// Each streamed message is large enough that the default HTTP/2 stream window (2 MiB on
/// the hyper client) fills after a few dozen messages, which is what the back-pressure
/// test observes.
const STREAM_PAYLOAD: usize = 64 * 1024;

pub enum Listener {
    Tcp(TcpListener),
    #[cfg(unix)]
    Unix(UnixListener),
    #[cfg(windows)]
    NamedPipe(PipeListener),
}

/// Serves `service` on `listener` until `shutdown` completes.
pub async fn serve(
    listener: Listener,
    service: TestService,
    tls: Option<ServerTlsConfig>,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> Result<(), tonic::transport::Error> {
    if tls.is_some() {
        // tonic builds the server rustls config from the process-default provider, which
        // nothing else installs. A second call returns the existing one.
        let _ = rustls::crypto::ring::default_provider().install_default();
    }
    let mut builder = Server::builder();
    if let Some(tls) = tls {
        builder = builder.tls_config(tls)?;
    }
    let router = builder.add_service(service);
    match listener {
        Listener::Tcp(listener) => {
            router
                .serve_with_incoming_shutdown(TcpListenerStream::new(listener), shutdown)
                .await
        }
        #[cfg(unix)]
        Listener::Unix(listener) => {
            router
                .serve_with_incoming_shutdown(UnixListenerStream::new(listener), shutdown)
                .await
        }
        #[cfg(windows)]
        Listener::NamedPipe(listener) => {
            router
                .serve_with_incoming_shutdown(listener.into_stream(), shutdown)
                .await
        }
    }
}

/// Self-signed certificate for `localhost` and `127.0.0.1`. The certificate PEM is also
/// the trust anchor a client's TLS transport uses.
#[derive(Debug, Clone)]
pub struct TestCert {
    pub certificate_pem: String,
    pub private_key_pem: String,
}

impl TestCert {
    pub fn generate() -> Self {
        let mut params = rcgen::CertificateParams::new(vec!["localhost".to_owned()])
            .expect("localhost is a valid name");
        params
            .subject_alt_names
            .push(rcgen::SanType::IpAddress(IpAddr::V4(
                std::net::Ipv4Addr::LOCALHOST,
            )));
        // webpki rejects a server certificate that is not marked for server auth.
        params
            .key_usages
            .push(rcgen::KeyUsagePurpose::DigitalSignature);
        params
            .extended_key_usages
            .push(rcgen::ExtendedKeyUsagePurpose::ServerAuth);
        let key = rcgen::KeyPair::generate().expect("generate a test key");
        let cert = params
            .self_signed(&key)
            .expect("self-sign the test certificate");
        TestCert {
            certificate_pem: cert.pem(),
            private_key_pem: key.serialize_pem(),
        }
    }

    pub fn server_config(&self) -> ServerTlsConfig {
        let _ = rustls::crypto::ring::default_provider().install_default();
        ServerTlsConfig::new().identity(Identity::from_pem(
            &self.certificate_pem,
            &self.private_key_pem,
        ))
    }
}

/// Counters a test can wait on. Values only increase, so a subscriber that arrives late
/// still observes an event that already happened.
#[derive(Debug)]
pub struct TestStats {
    sleep_started: watch::Sender<u64>,
    sleep_finished: watch::Sender<u64>,
    sleep_cancelled: watch::Sender<u64>,
    stream_sent: watch::Sender<u64>,
    stream_cancelled: watch::Sender<u64>,
    /// Request messages read by `Collect` and `Chat`.
    requests_received: watch::Sender<u64>,
    collect_ended: watch::Sender<u64>,
    chat_cancelled: watch::Sender<u64>,
}

impl TestStats {
    fn new() -> Self {
        TestStats {
            sleep_started: watch::channel(0).0,
            sleep_finished: watch::channel(0).0,
            sleep_cancelled: watch::channel(0).0,
            stream_sent: watch::channel(0).0,
            stream_cancelled: watch::channel(0).0,
            requests_received: watch::channel(0).0,
            collect_ended: watch::channel(0).0,
            chat_cancelled: watch::channel(0).0,
        }
    }

    fn bump(sender: &watch::Sender<u64>) {
        sender.send_modify(|value| *value += 1);
    }

    pub fn sleep_started(&self) -> watch::Receiver<u64> {
        self.sleep_started.subscribe()
    }

    pub fn sleep_finished(&self) -> watch::Receiver<u64> {
        self.sleep_finished.subscribe()
    }

    pub fn sleep_cancelled(&self) -> watch::Receiver<u64> {
        self.sleep_cancelled.subscribe()
    }

    pub fn stream_sent(&self) -> watch::Receiver<u64> {
        self.stream_sent.subscribe()
    }

    pub fn stream_cancelled(&self) -> watch::Receiver<u64> {
        self.stream_cancelled.subscribe()
    }

    pub fn requests_received(&self) -> watch::Receiver<u64> {
        self.requests_received.subscribe()
    }

    pub fn collect_ended(&self) -> watch::Receiver<u64> {
        self.collect_ended.subscribe()
    }

    pub fn chat_cancelled(&self) -> watch::Receiver<u64> {
        self.chat_cancelled.subscribe()
    }

    /// Text form of [`/kurpc.test.Test/Stats`](Self), one `name=count` line each.
    pub fn text(&self) -> String {
        [
            ("sleep_started", &self.sleep_started),
            ("sleep_finished", &self.sleep_finished),
            ("sleep_cancelled", &self.sleep_cancelled),
            ("stream_sent", &self.stream_sent),
            ("stream_cancelled", &self.stream_cancelled),
            ("requests_received", &self.requests_received),
            ("collect_ended", &self.collect_ended),
            ("chat_cancelled", &self.chat_cancelled),
        ]
        .iter()
        .map(|(name, counter)| format!("{name}={}\n", *counter.borrow()))
        .collect()
    }
}

#[derive(Debug, Clone)]
pub struct TestService {
    stats: Arc<TestStats>,
}

impl Default for TestService {
    fn default() -> Self {
        Self::new()
    }
}

impl TestService {
    pub fn new() -> Self {
        TestService {
            stats: Arc::new(TestStats::new()),
        }
    }

    pub fn stats(&self) -> Arc<TestStats> {
        Arc::clone(&self.stats)
    }
}

impl NamedService for TestService {
    const NAME: &'static str = SERVICE;
}

impl Service<http::Request<Body>> for TestService {
    type Response = http::Response<Body>;
    type Error = Infallible;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Infallible>> + Send>>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Infallible>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<Body>) -> Self::Future {
        let stats = Arc::clone(&self.stats);
        Box::pin(async move {
            let mut grpc = Grpc::new(PassthroughCodec);
            let method = request.uri().path().strip_prefix(&format!("/{SERVICE}/"));
            let response = match method {
                Some("Echo") => grpc.unary(service_fn(echo), request).await,
                Some("Fail") => grpc.unary(service_fn(fail), request).await,
                Some("Sleep") => {
                    let stats = Arc::clone(&stats);
                    grpc.unary(
                        service_fn(move |request| sleep(Arc::clone(&stats), request)),
                        request,
                    )
                    .await
                }
                Some("Stats") => {
                    let body = Bytes::from(stats.text());
                    grpc.unary(
                        service_fn(move |_request| {
                            let body = body.clone();
                            async move { Ok(Response::new(body)) }
                        }),
                        request,
                    )
                    .await
                }
                Some("Scheme") => {
                    let scheme = Bytes::from(request.uri().scheme_str().unwrap_or("").to_owned());
                    grpc.unary(
                        service_fn(move |_request| {
                            let scheme = scheme.clone();
                            async move { Ok(Response::new(scheme)) }
                        }),
                        request,
                    )
                    .await
                }
                Some("Count") => {
                    let stats = Arc::clone(&stats);
                    grpc.server_streaming(
                        service_fn(move |request| count(Arc::clone(&stats), request)),
                        request,
                    )
                    .await
                }
                Some("Infinite") => {
                    let stats = Arc::clone(&stats);
                    grpc.server_streaming(
                        service_fn(move |_request| {
                            let stats = Arc::clone(&stats);
                            async move { Ok(Response::new(MessageStream::infinite(stats))) }
                        }),
                        request,
                    )
                    .await
                }
                Some("Collect") => {
                    let stats = Arc::clone(&stats);
                    grpc.client_streaming(
                        service_fn(move |request| collect(Arc::clone(&stats), request)),
                        request,
                    )
                    .await
                }
                Some("Chat") => {
                    let stats = Arc::clone(&stats);
                    grpc.streaming(
                        service_fn(move |request: Request<Streaming<Bytes>>| {
                            let stats = Arc::clone(&stats);
                            async move {
                                Ok(Response::new(ChatStream::new(stats, request.into_inner())))
                            }
                        }),
                        request,
                    )
                    .await
                }
                _ => Status::unimplemented(request.uri().path().to_owned()).into_http(),
            };
            Ok(response)
        })
    }
}

async fn echo(request: Request<Bytes>) -> Result<Response<Bytes>, Status> {
    let (metadata, _, message) = request.into_parts();
    let mut response = Response::new(message);
    *response.metadata_mut() = metadata;
    Ok(response)
}

async fn fail(request: Request<Bytes>) -> Result<Response<Bytes>, Status> {
    let body = request.into_inner();
    let body = std::str::from_utf8(&body)
        .map_err(|_| Status::invalid_argument("Fail expects a UTF-8 body"))?;
    let (code, message) = body
        .split_once(':')
        .ok_or_else(|| Status::invalid_argument("Fail expects <code>:<message>"))?;
    let code = code
        .parse::<i32>()
        .map_err(|_| Status::invalid_argument(format!("bad status code {code:?}")))?;
    Err(Status::new(Code::from_i32(code), message))
}

async fn sleep(stats: Arc<TestStats>, request: Request<Bytes>) -> Result<Response<Bytes>, Status> {
    let body = request.into_inner();
    let text = std::str::from_utf8(&body)
        .map_err(|_| Status::invalid_argument("Sleep expects a UTF-8 body"))?;
    let millis: u64 = text
        .parse()
        .map_err(|_| Status::invalid_argument("Sleep expects decimal milliseconds"))?;
    TestStats::bump(&stats.sleep_started);
    // Dropped if the handler future is cancelled, which is how the client reset arrives.
    let mut guard = CancelOnDrop::new(&stats, |stats| &stats.sleep_cancelled);
    tokio::time::sleep(Duration::from_millis(millis)).await;
    guard.armed = false;
    TestStats::bump(&stats.sleep_finished);
    Ok(Response::new(body))
}

/// Bumps `counter` when dropped while still armed. For a handler, that is the handler future
/// being dropped, which is how a client reset reaches the server.
struct CancelOnDrop {
    stats: Arc<TestStats>,
    counter: fn(&TestStats) -> &watch::Sender<u64>,
    armed: bool,
}

impl CancelOnDrop {
    fn new(stats: &Arc<TestStats>, counter: fn(&TestStats) -> &watch::Sender<u64>) -> Self {
        CancelOnDrop {
            stats: Arc::clone(stats),
            counter,
            armed: true,
        }
    }
}

impl Drop for CancelOnDrop {
    fn drop(&mut self) {
        if self.armed {
            TestStats::bump((self.counter)(&self.stats));
        }
    }
}

async fn collect(
    stats: Arc<TestStats>,
    request: Request<Streaming<Bytes>>,
) -> Result<Response<Bytes>, Status> {
    // Counts every exit. tonic reads a client's RST_STREAM(CANCEL) on the request stream as
    // its end, so a handler cannot tell a cancel from a half-close; that it ends at all is
    // what a test can check.
    let _ended = CancelOnDrop::new(&stats, |stats| &stats.collect_ended);
    let mut requests = request.into_inner();
    let (mut count, mut bytes) = (0u64, 0usize);
    while let Some(message) = requests.message().await? {
        TestStats::bump(&stats.requests_received);
        if message == "fail" {
            return Err(Status::invalid_argument("fail requested"));
        }
        count += 1;
        bytes += message.len();
    }
    Ok(Response::new(Bytes::from(format!("{count}:{bytes}"))))
}

/// Echoes each request message until the client half-closes or sends `bye`. Dropped before
/// either is a cancellation.
struct ChatStream {
    stats: Arc<TestStats>,
    requests: Streaming<Bytes>,
    finished: bool,
}

impl ChatStream {
    fn new(stats: Arc<TestStats>, requests: Streaming<Bytes>) -> Self {
        ChatStream {
            stats,
            requests,
            finished: false,
        }
    }
}

impl Stream for ChatStream {
    type Item = Result<Bytes, Status>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = self.get_mut();
        if this.finished {
            return Poll::Ready(None);
        }
        let next = match Pin::new(&mut this.requests).poll_next(cx) {
            Poll::Pending => return Poll::Pending,
            Poll::Ready(next) => next,
        };
        match next {
            Some(Ok(message)) => {
                TestStats::bump(&this.stats.requests_received);
                if message == "bye" {
                    this.finished = true;
                    return Poll::Ready(None);
                }
                Poll::Ready(Some(Ok(message)))
            }
            Some(Err(status)) => {
                this.finished = true;
                Poll::Ready(Some(Err(status)))
            }
            None => {
                this.finished = true;
                Poll::Ready(None)
            }
        }
    }
}

impl Drop for ChatStream {
    fn drop(&mut self) {
        if !self.finished {
            TestStats::bump(&self.stats.chat_cancelled);
        }
    }
}

async fn count(
    stats: Arc<TestStats>,
    request: Request<Bytes>,
) -> Result<Response<MessageStream>, Status> {
    let body = request.into_inner();
    let text = std::str::from_utf8(&body)
        .map_err(|_| Status::invalid_argument("Count expects a UTF-8 body"))?;
    let (count, fail_after) = match text.split_once(':') {
        Some((count, fail_after)) => (count, Some(fail_after)),
        None => (text, None),
    };
    let count: u64 = count
        .parse()
        .map_err(|_| Status::invalid_argument("Count expects <n> or <n>:<k>"))?;
    let fail_after = match fail_after {
        Some(fail_after) => Some(
            fail_after
                .parse()
                .map_err(|_| Status::invalid_argument("Count expects <n> or <n>:<k>"))?,
        ),
        None => None,
    };
    // `<n>` sends n messages and ends OK. `<n>:<k>` sends min(n, k) and then Aborted,
    // so `k == 0` fails before any message and `k > n` still fails, after n messages.
    let ok = match fail_after {
        Some(fail_after) => count.min(fail_after),
        None => count,
    };
    Ok(Response::new(MessageStream {
        stats,
        index: 0,
        ok,
        fail: fail_after.is_some(),
        finished: false,
    }))
}

/// A stream of fixed-size payloads. `ok` messages are yielded, then either the stream
/// ends or, when `fail` is set, one `Aborted` status. Drop before that is a cancellation.
struct MessageStream {
    stats: Arc<TestStats>,
    index: u64,
    ok: u64,
    fail: bool,
    finished: bool,
}

impl MessageStream {
    fn infinite(stats: Arc<TestStats>) -> Self {
        MessageStream {
            stats,
            index: 0,
            ok: u64::MAX,
            fail: false,
            finished: false,
        }
    }
}

impl Stream for MessageStream {
    type Item = Result<Bytes, Status>;

    fn poll_next(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = self.get_mut();
        if this.finished {
            return Poll::Ready(None);
        }
        if this.index >= this.ok {
            this.finished = true;
            if this.fail {
                return Poll::Ready(Some(Err(Status::new(
                    Code::Aborted,
                    format!("stopped after {}", this.ok),
                ))));
            }
            return Poll::Ready(None);
        }
        let message = stream_payload(this.index);
        this.index += 1;
        TestStats::bump(&this.stats.stream_sent);
        Poll::Ready(Some(Ok(message)))
    }
}

impl Drop for MessageStream {
    fn drop(&mut self) {
        if !self.finished {
            TestStats::bump(&self.stats.stream_cancelled);
        }
    }
}

fn stream_payload(index: u64) -> Bytes {
    let prefix = index.to_string();
    let mut body = vec![b' '; STREAM_PAYLOAD];
    body[..prefix.len()].copy_from_slice(prefix.as_bytes());
    Bytes::from(body)
}

#[cfg(windows)]
pub struct PipeListener {
    name: String,
    first: tokio::net::windows::named_pipe::NamedPipeServer,
}

#[cfg(windows)]
impl PipeListener {
    /// Creates the first pipe instance. Clients that connect before this returns see
    /// `NotFound` rather than `ERROR_PIPE_BUSY`.
    pub fn bind(name: impl Into<String>) -> std::io::Result<Self> {
        let name = name.into();
        let first = tokio::net::windows::named_pipe::ServerOptions::new()
            .first_pipe_instance(true)
            .create(&name)?;
        Ok(PipeListener { name, first })
    }

    fn into_stream(self) -> tokio_stream::wrappers::ReceiverStream<std::io::Result<PipeIo>> {
        let (sender, receiver) = tokio::sync::mpsc::channel(8);
        tokio::spawn(async move {
            let mut server = self.first;
            loop {
                if let Err(error) = server.connect().await {
                    let _ = sender.send(Err(error)).await;
                    return;
                }
                let connected = server;
                // The next instance has to exist before the connected one is handed off,
                // or a client that races the hand-off gets `NotFound` instead of
                // `ERROR_PIPE_BUSY` (tokio's named-pipe listen loop).
                server = match tokio::net::windows::named_pipe::ServerOptions::new()
                    .create(&self.name)
                {
                    Ok(server) => server,
                    Err(error) => {
                        let _ = sender.send(Err(error)).await;
                        return;
                    }
                };
                if sender.send(Ok(PipeIo(connected))).await.is_err() {
                    return;
                }
            }
        });
        tokio_stream::wrappers::ReceiverStream::new(receiver)
    }
}

/// tonic's server requires [`Connected`](tonic::transport::server::Connected), which
/// `NamedPipeServer` does not implement.
#[cfg(windows)]
struct PipeIo(tokio::net::windows::named_pipe::NamedPipeServer);

#[cfg(windows)]
impl tokio::io::AsyncRead for PipeIo {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_read(cx, buf)
    }
}

#[cfg(windows)]
impl tokio::io::AsyncWrite for PipeIo {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        Pin::new(&mut self.0).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_shutdown(cx)
    }
}

#[cfg(windows)]
impl tonic::transport::server::Connected for PipeIo {
    type ConnectInfo = ();

    fn connect_info(&self) -> Self::ConnectInfo {}
}
