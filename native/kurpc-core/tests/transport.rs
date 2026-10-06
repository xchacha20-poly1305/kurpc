mod common;

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use kurpc_core::{CallOptions, Channel, ChannelConfig, Dialed, TransportConfig};
use tokio::net::TcpStream;

use common::{ECHO, Server, unary};

#[tokio::test]
async fn tcp_round_trip() {
    let server = Server::tcp().await;
    let response = unary(&server.channel(), ECHO, b"tcp", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "tcp");
}

#[cfg(unix)]
#[tokio::test]
async fn unix_round_trip() {
    let server = Server::unix().await;
    let response = unary(&server.channel(), ECHO, b"unix", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "unix");
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[tokio::test]
async fn abstract_unix_round_trip() {
    use std::os::linux::net::SocketAddrExt;
    use std::os::unix::net::SocketAddr;

    let name = format!("kurpc-core-abstract-{}", std::process::id());
    let address = SocketAddr::from_abstract_name(name.as_bytes()).unwrap();
    let listener = std::os::unix::net::UnixListener::bind_addr(&address).unwrap();
    let server = Server::from_unix_std(listener, std::path::PathBuf::from(format!("\0{name}")));

    let channel = Channel::new(ChannelConfig::new(TransportConfig::Unix {
        path: format!("\0{name}"),
    }))
    .unwrap();
    let response = unary(&channel, ECHO, b"abstract", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "abstract");
    drop(server);
}

#[cfg(unix)]
#[tokio::test]
async fn custom_stream_round_trip() {
    let server = Server::unix().await;
    let path = server.unix_path().to_owned();
    let dialer = Arc::new(StreamDialer { path });
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(dialer))).unwrap();
    let response = unary(&channel, ECHO, b"stream", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "stream");
}

#[cfg(unix)]
#[tokio::test]
async fn custom_fd_round_trip() {
    let server = Server::unix().await;
    let path = server.unix_path().to_owned();
    let dialer = Arc::new(FdDialer { path });
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(dialer))).unwrap();
    let response = unary(&channel, ECHO, b"fd", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "fd");
}

#[cfg(unix)]
struct StreamDialer {
    path: std::path::PathBuf,
}

#[cfg(unix)]
impl kurpc_core::CustomDialer for StreamDialer {
    fn dial(&self) -> Pin<Box<dyn Future<Output = std::io::Result<Dialed>> + Send + '_>> {
        let path = self.path.clone();
        Box::pin(async move {
            let stream = tokio::net::UnixStream::connect(path).await?;
            Ok(Dialed::Stream(Box::new(stream)))
        })
    }
}

#[cfg(unix)]
struct FdDialer {
    path: std::path::PathBuf,
}

#[cfg(unix)]
impl kurpc_core::CustomDialer for FdDialer {
    fn dial(&self) -> Pin<Box<dyn Future<Output = std::io::Result<Dialed>> + Send + '_>> {
        let path = self.path.clone();
        Box::pin(async move {
            // Connect on the runtime, then hand the owned fd back so kurpc has to detect
            // the family and clear the blocking flag itself.
            let stream = tokio::net::UnixStream::connect(path).await?;
            let std_stream = stream.into_std()?;
            Ok(Dialed::Fd(std::os::fd::OwnedFd::from(std_stream)))
        })
    }
}

#[tokio::test]
async fn custom_tcp_stream_round_trip() {
    let server = Server::tcp().await;
    let port = server.port();
    let dialer = Arc::new(TcpDialer { port });
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(dialer))).unwrap();
    let response = unary(&channel, ECHO, b"tcp-dial", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "tcp-dial");
}

struct TcpDialer {
    port: u16,
}

impl kurpc_core::CustomDialer for TcpDialer {
    fn dial(&self) -> Pin<Box<dyn Future<Output = std::io::Result<Dialed>> + Send + '_>> {
        let port = self.port;
        Box::pin(async move {
            let stream = TcpStream::connect(("127.0.0.1", port)).await?;
            stream.set_nodelay(true)?;
            Ok(Dialed::Stream(Box::new(stream)))
        })
    }
}
