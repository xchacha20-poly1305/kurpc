mod common;

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use kurpc_core::{CallOptions, Channel, ChannelConfig, Code, Dialed, Error, TransportConfig};
use tokio::net::TcpStream;
use tokio::sync::watch;

use common::{ECHO, FAIL, Server, assert_status, unary, wait_counter};

#[tokio::test]
async fn wait_for_ready_connects_once_the_server_is_up() {
    wait_for_ready_until_released(Blocked::Refuse).await;
}

/// On Windows a dial to a closed localhost port is not refused at once: the SYN is retried
/// for about two seconds, so a shorter connect timeout ends the attempt instead.
/// Upstream issue: https://github.com/grpc/grpc-rust/issues/2919
#[tokio::test]
async fn wait_for_ready_retries_a_dial_that_hits_the_connect_timeout() {
    wait_for_ready_until_released(Blocked::Hang).await;
}

async fn wait_for_ready_until_released(blocked: Blocked) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);

    let (attempts_tx, mut attempts) = watch::channel(0);
    let (release_tx, release) = watch::channel(false);
    let dialer = Arc::new(GatedDialer {
        port,
        blocked,
        attempts: attempts_tx,
        release,
    });
    let mut config = ChannelConfig::new(TransportConfig::Custom(dialer));
    config.connect_timeout = Duration::from_millis(100);
    let channel = Channel::new(config).unwrap();
    let options = CallOptions {
        wait_for_ready: true,
        timeout: Some(Duration::from_secs(3)),
        ..CallOptions::default()
    };
    let pending = tokio::spawn({
        let channel = channel.clone();
        async move { unary(&channel, ECHO, b"ready", options).await }
    });
    wait_counter(&mut attempts, 1).await;

    let _server = Server::tcp_on(port).await;
    release_tx.send_replace(true);

    let response = pending.await.unwrap().unwrap();
    assert_eq!(response.message, "ready");
}

#[tokio::test]
async fn without_wait_for_ready_a_refused_connection_fails_fast() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Tcp {
        host: "127.0.0.1".to_owned(),
        port,
    }))
    .unwrap();
    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Transport { .. }), "{error:?}");
}

#[tokio::test]
async fn a_status_after_the_request_is_sent_is_not_retried() {
    let server = Server::tcp().await;
    let (attempts_tx, attempts) = watch::channel(0);
    let dialer = Arc::new(CountingDialer {
        port: server.port(),
        attempts: attempts_tx,
    });
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(dialer))).unwrap();
    let options = CallOptions {
        wait_for_ready: true,
        timeout: Some(Duration::from_secs(2)),
        ..CallOptions::default()
    };
    let error = unary(&channel, FAIL, b"5:once", options).await.unwrap_err();
    assert_status(error, Code::NotFound);
    // The dial already happened, and the status must not cause another one.
    assert_eq!(*attempts.borrow(), 1);
}

/// How [`GatedDialer`] fails a dial before `release` is set.
#[derive(Clone, Copy)]
enum Blocked {
    /// An `io::Error` from the connector, the class tonic wraps as `ConnectError`.
    Refuse,
    /// Never completes, so the connect timeout drops the dial.
    Hang,
}

/// Fails the dial as `blocked` says until `release` is set, then connects for real.
struct GatedDialer {
    port: u16,
    blocked: Blocked,
    attempts: watch::Sender<u64>,
    release: watch::Receiver<bool>,
}

impl kurpc_core::CustomDialer for GatedDialer {
    fn dial(&self) -> Pin<Box<dyn Future<Output = std::io::Result<Dialed>> + Send + '_>> {
        let port = self.port;
        let blocked = self.blocked;
        let attempts = self.attempts.clone();
        let release = self.release.clone();
        Box::pin(async move {
            attempts.send_modify(|count| *count += 1);
            if !*release.borrow() {
                return match blocked {
                    Blocked::Refuse => Err(std::io::Error::new(
                        std::io::ErrorKind::ConnectionRefused,
                        "server not started",
                    )),
                    Blocked::Hang => std::future::pending().await,
                };
            }
            let stream = TcpStream::connect(("127.0.0.1", port)).await?;
            stream.set_nodelay(true)?;
            Ok(Dialed::Stream(Box::new(stream)))
        })
    }
}

struct CountingDialer {
    port: u16,
    attempts: watch::Sender<u64>,
}

impl kurpc_core::CustomDialer for CountingDialer {
    fn dial(&self) -> Pin<Box<dyn Future<Output = std::io::Result<Dialed>> + Send + '_>> {
        let port = self.port;
        let attempts = self.attempts.clone();
        Box::pin(async move {
            attempts.send_modify(|count| *count += 1);
            let stream = TcpStream::connect(("127.0.0.1", port)).await?;
            stream.set_nodelay(true)?;
            Ok(Dialed::Stream(Box::new(stream)))
        })
    }
}
