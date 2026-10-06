//! Shared fixtures for the kurpc-core integration tests.
//!
//! Each test binary compiles this module on its own, so helpers that one file does not use
//! are dead there. The allow is on the module for that reason.

#![allow(dead_code)]

#[cfg(unix)]
use std::path::PathBuf;
#[cfg(unix)]
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use kurpc_core::{
    CallHandle, CallOptions, Channel, ChannelConfig, Error, Metadata, Response, Result, StreamSink,
    TransportConfig,
};
use kurpc_testserver::{Listener, TestService, TestStats};
use tokio::net::TcpListener;
use tokio::sync::{oneshot, watch};
use tokio::task::JoinHandle;

#[cfg(unix)]
use tokio::net::UnixListener;

pub const ECHO: &str = "/kurpc.test.Test/Echo";
pub const FAIL: &str = "/kurpc.test.Test/Fail";
pub const SLEEP: &str = "/kurpc.test.Test/Sleep";
pub const COUNT: &str = "/kurpc.test.Test/Count";
pub const INFINITE: &str = "/kurpc.test.Test/Infinite";
pub const STATS: &str = "/kurpc.test.Test/Stats";
pub const SCHEME: &str = "/kurpc.test.Test/Scheme";
pub const COLLECT: &str = "/kurpc.test.Test/Collect";
pub const CHAT: &str = "/kurpc.test.Test/Chat";

/// A failure detector, not a synchronization delay. Events are awaited directly.
const HANG: Duration = Duration::from_secs(3);

/// In-process server. Dropping it asks the server to shut down and, for a Unix socket,
/// unlinks the path. [`Server::shutdown`] waits until the process has released the address.
pub struct Server {
    pub stats: Arc<TestStats>,
    kind: Kind,
    stop: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<std::result::Result<(), tonic::transport::Error>>>,
}

enum Kind {
    Tcp {
        port: u16,
    },
    #[cfg(unix)]
    Unix {
        path: PathBuf,
    },
}

impl Server {
    pub async fn tcp() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        Self::spawn(Listener::Tcp(listener), Kind::Tcp { port })
    }

    pub async fn tcp_on(port: u16) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", port)).await.unwrap();
        Self::spawn(Listener::Tcp(listener), Kind::Tcp { port })
    }

    #[cfg(unix)]
    pub async fn unix() -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "kurpc-core-{}-{}.sock",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        Self::unix_at(path).await
    }

    #[cfg(unix)]
    pub fn from_unix_std(listener: std::os::unix::net::UnixListener, path: PathBuf) -> Self {
        listener.set_nonblocking(true).unwrap();
        let listener = UnixListener::from_std(listener).unwrap();
        Self::spawn(Listener::Unix(listener), Kind::Unix { path })
    }

    #[cfg(unix)]
    pub async fn unix_at(path: PathBuf) -> Self {
        let _ = std::fs::remove_file(&path);
        let listener = UnixListener::bind(&path).unwrap();
        Self::spawn(Listener::Unix(listener), Kind::Unix { path })
    }

    #[cfg(unix)]
    pub fn unix_path(&self) -> &std::path::Path {
        match &self.kind {
            Kind::Unix { path } => path,
            Kind::Tcp { .. } => panic!("not a unix server"),
        }
    }

    pub fn port(&self) -> u16 {
        match &self.kind {
            Kind::Tcp { port } => *port,
            #[cfg(unix)]
            Kind::Unix { .. } => panic!("not a tcp server"),
        }
    }

    fn spawn(listener: Listener, kind: Kind) -> Self {
        let service = TestService::new();
        let stats = service.stats();
        let (stop, stopped) = oneshot::channel();
        let task = tokio::spawn(async move {
            kurpc_testserver::serve(listener, service, None, async move {
                let _ = stopped.await;
            })
            .await
        });
        Server {
            stats,
            kind,
            stop: Some(stop),
            task: Some(task),
        }
    }

    pub fn config(&self) -> ChannelConfig {
        match &self.kind {
            Kind::Tcp { port } => ChannelConfig::new(TransportConfig::Tcp {
                host: "127.0.0.1".to_owned(),
                port: *port,
            }),
            #[cfg(unix)]
            Kind::Unix { path } => ChannelConfig::new(TransportConfig::Unix {
                path: path.to_str().unwrap().to_owned(),
            }),
        }
    }

    pub fn channel(&self) -> Channel {
        Channel::new(self.config()).unwrap()
    }

    pub async fn shutdown(mut self) {
        // Signal first, then wait until the listener is gone. `Drop` unlinks the socket
        // afterwards; moving the fields out is not possible while `Drop` is implemented.
        drop(self.stop.take());
        let Some(task) = self.task.take() else {
            return;
        };
        match tokio::time::timeout(HANG, task).await {
            Ok(Ok(Ok(()))) => {}
            Ok(Ok(Err(error))) => panic!("server exited: {error}"),
            Ok(Err(error)) => panic!("server task: {error}"),
            Err(_) => panic!("server did not shut down"),
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop.take();
        #[cfg(unix)]
        if let Kind::Unix { path } = &self.kind {
            let _ = std::fs::remove_file(path);
        }
    }
}

pub async fn unary(
    channel: &Channel,
    method: &str,
    request: impl AsRef<[u8]>,
    options: CallOptions,
) -> Result<Response> {
    let (done, result) = oneshot::channel();
    channel.unary(
        method,
        Bytes::copy_from_slice(request.as_ref()),
        options,
        move |outcome| {
            let _ = done.send(outcome);
        },
    );
    tokio::time::timeout(HANG, result)
        .await
        .expect("call did not finish")
        .expect("callback dropped without running")
}

pub struct LiveStream {
    pub headers: watch::Receiver<u64>,
    pub messages: Arc<Mutex<Vec<Bytes>>>,
    pub received: watch::Receiver<u64>,
    pub done: oneshot::Receiver<Result<Metadata>>,
    pub handle: CallHandle,
}

pub fn server_streaming(
    channel: &Channel,
    method: &str,
    request: impl AsRef<[u8]>,
    options: CallOptions,
) -> LiveStream {
    let request = Bytes::copy_from_slice(request.as_ref());
    live_stream(|sink| channel.server_streaming(method, request, options, sink))
}

pub fn bidi_streaming(channel: &Channel, method: &str, options: CallOptions) -> LiveStream {
    live_stream(|sink| channel.bidi_streaming(method, options, sink))
}

fn live_stream(start: impl FnOnce(ProbeSink) -> CallHandle) -> LiveStream {
    let (headers_tx, headers) = watch::channel(0);
    let (received_tx, received) = watch::channel(0);
    let messages = Arc::new(Mutex::new(Vec::new()));
    let (done_tx, done) = oneshot::channel();
    let handle = start(ProbeSink {
        headers: headers_tx,
        messages: Arc::clone(&messages),
        received: received_tx,
        done: Some(done_tx),
    });
    LiveStream {
        headers,
        messages,
        received,
        done,
        handle,
    }
}

/// A client-streaming call and the receiver of its single outcome.
pub fn client_streaming(
    channel: &Channel,
    method: &str,
    options: CallOptions,
) -> (CallHandle, oneshot::Receiver<Result<Response>>) {
    let (done, result) = oneshot::channel();
    let handle = channel.client_streaming(method, options, move |outcome| {
        let _ = done.send(outcome);
    });
    (handle, result)
}

pub async fn call_result(result: oneshot::Receiver<Result<Response>>) -> Result<Response> {
    tokio::time::timeout(HANG, result)
        .await
        .expect("call did not finish")
        .expect("callback dropped without running")
}

/// [`CallHandle::send`] as a future: whether the message was queued.
pub async fn send(handle: &CallHandle, message: impl AsRef<[u8]>) -> bool {
    let (done, queued) = oneshot::channel();
    handle.send(Bytes::copy_from_slice(message.as_ref()), move |ok| {
        let _ = done.send(ok);
    });
    tokio::time::timeout(HANG, queued)
        .await
        .expect("send did not finish")
        .expect("send callback dropped without running")
}

struct ProbeSink {
    headers: watch::Sender<u64>,
    messages: Arc<Mutex<Vec<Bytes>>>,
    received: watch::Sender<u64>,
    done: Option<oneshot::Sender<Result<Metadata>>>,
}

impl StreamSink for ProbeSink {
    fn on_headers(&mut self, _headers: Metadata) {
        self.headers.send_modify(|count| *count += 1);
    }

    fn on_message(&mut self, message: Bytes) {
        self.messages.lock().expect("message lock").push(message);
        self.received.send_modify(|count| *count += 1);
    }

    fn on_complete(&mut self, result: Result<Metadata>) {
        if let Some(done) = self.done.take() {
            let _ = done.send(result);
        }
    }
}

pub async fn wait_counter(rx: &mut watch::Receiver<u64>, at_least: u64) {
    tokio::time::timeout(HANG, async {
        loop {
            if *rx.borrow() >= at_least {
                return;
            }
            rx.changed().await.expect("counter closed");
        }
    })
    .await
    .expect("timed out waiting for a counter");
}

/// Resolves once `rx` has held one value for `quiet`. A stream that is still producing
/// keeps resetting the quiet window, so the timeout is the stall signal itself: production
/// has no separate event for "nothing more was pulled".
pub async fn wait_quiescent(rx: &mut watch::Receiver<u64>, quiet: Duration) -> u64 {
    tokio::time::timeout(HANG, async {
        loop {
            let value = *rx.borrow_and_update();
            match tokio::time::timeout(quiet, rx.changed()).await {
                Err(_) => return value,
                Ok(Err(_)) => return value,
                Ok(Ok(())) => {}
            }
        }
    })
    .await
    .expect("stream never went quiet")
}

pub async fn stream_result(done: oneshot::Receiver<Result<Metadata>>) -> Result<Metadata> {
    tokio::time::timeout(HANG, done)
        .await
        .expect("stream did not finish")
        .expect("callback dropped without running")
}

pub fn message_index(message: &Bytes) -> u64 {
    let text = std::str::from_utf8(message).unwrap();
    text.split_whitespace().next().unwrap().parse().unwrap()
}

pub fn assert_status(error: Error, code: kurpc_core::Code) -> String {
    match error {
        Error::Status {
            code: got, message, ..
        } => {
            assert_eq!(got, code, "{message}");
            message
        }
        other => panic!("expected status {code:?}, got {other:?}"),
    }
}
