//! Windows AF_UNIX sockets use readiness-based I/O; Tokio only exposes UDS on Unix.
use std::io;
use std::net::Shutdown;
use std::pin::Pin;
use std::task::{Context, Poll};

use async_io::Async;
use socket2::{Domain, SockAddr, Socket, Type};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

pub(super) struct UnixStream(Async<Socket>);

impl UnixStream {
    pub(super) async fn connect(path: &str) -> io::Result<Self> {
        let address = SockAddr::unix(path)?;
        let socket = Socket::new(Domain::UNIX, Type::STREAM, None)?;
        socket.set_nonblocking(true)?;
        match socket.connect(&address) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
            Err(error) => return Err(error),
        }
        // The future owns both registration and socket, so cancellation closes them together.
        let socket = Async::new_nonblocking(socket)?;
        socket.writable().await?;
        if let Some(error) = socket.get_ref().take_error()? {
            return Err(error);
        }
        Ok(Self(socket))
    }
}

impl AsyncRead for UnixStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        match futures_io::AsyncRead::poll_read(
            Pin::new(&mut &self.0),
            cx,
            buf.initialize_unfilled(),
        ) {
            Poll::Ready(Ok(count)) => {
                buf.advance(count);
                Poll::Ready(Ok(()))
            }
            Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl AsyncWrite for UnixStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        futures_io::AsyncWrite::poll_write(Pin::new(&mut &self.0), cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        futures_io::AsyncWrite::poll_flush(Pin::new(&mut &self.0), cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(self.0.get_ref().shutdown(Shutdown::Write))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{CallOptions, Channel, ChannelConfig, TransportConfig};
    use bytes::Bytes;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};
    use tokio::sync::oneshot;

    struct Listener {
        socket: Option<Async<Socket>>,
        path: PathBuf,
    }

    impl Listener {
        fn new() -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let path = std::env::temp_dir().join(format!(
                "kurpc-测试-{}-{}.sock",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let socket = Socket::new(Domain::UNIX, Type::STREAM, None).unwrap();
            socket.bind(&SockAddr::unix(&path).unwrap()).unwrap();
            socket.listen(16).unwrap();
            Self {
                socket: Some(Async::new(socket).unwrap()),
                path,
            }
        }

        async fn accept(&self) -> UnixStream {
            let (socket, _) = self
                .socket
                .as_ref()
                .unwrap()
                .read_with(|socket| socket.accept())
                .await
                .unwrap();
            UnixStream(Async::new(socket).unwrap())
        }
    }

    impl Drop for Listener {
        fn drop(&mut self) {
            drop(self.socket.take());
            let _ = std::fs::remove_file(&self.path);
        }
    }

    #[tokio::test]
    async fn windows_uds_grpc_round_trip() {
        let tcp = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = tcp.local_addr().unwrap();
        let (stop, stopped) = oneshot::channel::<()>();
        let server = tokio::spawn(kurpc_testserver::serve(
            kurpc_testserver::Listener::Tcp(tcp),
            kurpc_testserver::TestService::new(),
            None,
            async {
                let _ = stopped.await;
            },
        ));
        let listener = Listener::new();
        let path = listener.path.to_str().unwrap().to_owned();
        let bridge = tokio::spawn(async move {
            for _ in 0..2 {
                let mut uds = listener.accept().await;
                let mut tcp = TcpStream::connect(address).await.unwrap();
                tokio::spawn(async move {
                    let _ = tokio::io::copy_bidirectional(&mut uds, &mut tcp).await;
                });
            }
        });
        // Reopening verifies that closing a channel releases its UDS connection.
        for _ in 0..2 {
            let channel = Channel::new(ChannelConfig::new(TransportConfig::Unix {
                path: path.clone(),
            }))
            .unwrap();
            let payload = Bytes::from(vec![b'x'; 256 * 1024]);
            let (done, result) = oneshot::channel();
            channel.unary(
                "/kurpc.test.Test/Echo",
                payload.clone(),
                CallOptions::default(),
                move |response| {
                    let _ = done.send(response);
                },
            );
            let response = tokio::time::timeout(Duration::from_secs(10), result)
                .await
                .unwrap()
                .unwrap()
                .unwrap();
            assert_eq!(response.message, payload);
            channel.close();
        }
        bridge.await.unwrap();
        drop(stop);
        server.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn windows_uds_shutdown_preserves_reads_and_existing_buffer() {
        let listener = Listener::new();
        let mut client = UnixStream::connect(listener.path.to_str().unwrap())
            .await
            .unwrap();
        let mut server = listener.accept().await;
        client.write_all(b"request").await.unwrap();
        client.shutdown().await.unwrap();
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"request");
        server.write_all(b"response").await.unwrap();
        server.shutdown().await.unwrap();
        let mut buffer = [0; 16];
        let mut read = ReadBuf::new(&mut buffer);
        read.put_slice(b"prefix:");
        while read.filled().len() < b"prefix:response".len() {
            let before = read.filled().len();
            std::future::poll_fn(|cx| Pin::new(&mut client).poll_read(cx, &mut read))
                .await
                .unwrap();
            assert!(read.filled().len() > before, "unexpected EOF");
        }
        assert_eq!(read.filled(), b"prefix:response");
    }

    #[tokio::test]
    async fn windows_uds_missing_socket_fails() {
        let listener = Listener::new();
        let path = listener.path.clone();
        drop(listener);
        assert!(UnixStream::connect(path.to_str().unwrap()).await.is_err());
    }
}
