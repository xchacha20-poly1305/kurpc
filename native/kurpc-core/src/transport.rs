use std::fmt;
use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
#[cfg(windows)]
use std::time::Duration;

use http::Uri;
use hyper_util::rt::TokioIo;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
#[cfg(unix)]
use tokio::net::UnixStream;
use tower::Service;

/// Where a channel's connections go. The endpoint URI only supplies `:authority` and the scheme;
/// the connector alone decides the address, so no transport other than TCP touches DNS.
#[derive(Clone)]
pub enum TransportConfig {
    Tcp {
        host: String,
        port: u16,
    },
    /// On Linux and Android a path starting with `'\0'` names an abstract socket
    /// (handled by tokio).
    #[cfg(unix)]
    Unix {
        path: String,
    },
    /// Full pipe path, for example `\\.\pipe\kurpc`.
    #[cfg(windows)]
    WindowsNamedPipe {
        name: String,
    },
    /// Invoked once per connection, including after a drop, so a custom transport reconnects
    /// the same way a built-in one does.
    Custom(Arc<dyn CustomDialer>),
}

impl fmt::Debug for TransportConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TransportConfig::Tcp { host, port } => f
                .debug_struct("Tcp")
                .field("host", host)
                .field("port", port)
                .finish(),
            #[cfg(unix)]
            TransportConfig::Unix { path } => f.debug_struct("Unix").field("path", path).finish(),
            #[cfg(windows)]
            TransportConfig::WindowsNamedPipe { name } => f
                .debug_struct("WindowsNamedPipe")
                .field("name", name)
                .finish(),
            TransportConfig::Custom(_) => f.write_str("Custom(..)"),
        }
    }
}

/// Dials one connection. Object-safe on purpose: a boxed future needs no `async-trait` crate,
/// and `Arc<dyn CustomDialer>` is what a binding layer stores.
pub trait CustomDialer: Send + Sync {
    fn dial(&self) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + '_>>;
}

/// What [`CustomDialer::dial`] hands back.
pub enum Dialed {
    /// Any bidirectional byte stream. The binding layer's duplex pipe is this variant.
    Stream(BoxedStream),
    /// An already-connected socket. kurpc takes ownership, sets it non-blocking, and drives it.
    #[cfg(unix)]
    Fd(std::os::fd::OwnedFd),
}

pub trait AsyncReadWrite: AsyncRead + AsyncWrite + Send + Unpin {}

impl<T: AsyncRead + AsyncWrite + Send + Unpin> AsyncReadWrite for T {}

pub type BoxedStream = Box<dyn AsyncReadWrite>;

impl TransportConfig {
    async fn connect(&self) -> io::Result<BoxedStream> {
        match self {
            TransportConfig::Tcp { host, port } => {
                let stream = TcpStream::connect((host.as_str(), *port)).await?;
                stream.set_nodelay(true)?;
                Ok(Box::new(stream))
            }
            #[cfg(unix)]
            TransportConfig::Unix { path } => Ok(Box::new(UnixStream::connect(path).await?)),
            #[cfg(windows)]
            TransportConfig::WindowsNamedPipe { name } => connect_named_pipe(name).await,
            TransportConfig::Custom(dialer) => match dialer.dial().await? {
                Dialed::Stream(stream) => Ok(stream),
                #[cfg(unix)]
                Dialed::Fd(fd) => unix::from_fd(fd),
            },
        }
    }
}

/// `ERROR_PIPE_BUSY`: the pipe exists, but every instance is currently connected.
#[cfg(windows)]
const ERROR_PIPE_BUSY: i32 = 231;

/// go-winio `DialPipeContext` retries `CreateFile` every 10ms while the context is alive instead
/// of calling `WaitNamedPipe` (which blocks a thread). The channel's connect timeout is applied
/// by tonic around this future, so dropping it is the deadline; there is no separate timer here.
#[cfg(windows)]
async fn connect_named_pipe(name: &str) -> io::Result<BoxedStream> {
    let retry = Duration::from_millis(10);
    loop {
        match tokio::net::windows::named_pipe::ClientOptions::new().open(name) {
            Ok(client) => return Ok(Box::new(client)),
            Err(error) if error.raw_os_error() == Some(ERROR_PIPE_BUSY) => {
                tokio::time::sleep(retry).await;
            }
            Err(error) => return Err(error),
        }
    }
}

#[cfg(unix)]
mod unix {
    use std::io;
    use std::mem::size_of;
    use std::os::fd::{AsRawFd, OwnedFd};

    use super::BoxedStream;

    pub(super) fn from_fd(fd: OwnedFd) -> io::Result<BoxedStream> {
        let domain = socket_domain(fd.as_raw_fd())?;
        // tokio rejects a blocking std socket. Set the flag before `from_std` checks it.
        set_nonblocking(fd.as_raw_fd())?;
        match domain {
            libc::AF_UNIX => {
                let stream = std::os::unix::net::UnixStream::from(fd);
                Ok(Box::new(tokio::net::UnixStream::from_std(stream)?))
            }
            libc::AF_INET | libc::AF_INET6 => {
                let stream = std::net::TcpStream::from(fd);
                stream.set_nodelay(true)?;
                Ok(Box::new(tokio::net::TcpStream::from_std(stream)?))
            }
            other => Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported socket domain {other}"),
            )),
        }
    }

    /// Family of a connected socket, from `getsockname`.
    fn socket_domain(fd: std::os::fd::RawFd) -> io::Result<libc::c_int> {
        let mut storage: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
        let mut len = size_of::<libc::sockaddr_storage>() as libc::socklen_t;
        // Safety: `storage` is a zeroed sockaddr_storage and `len` is its capacity.
        let rc = unsafe {
            libc::getsockname(
                fd,
                &mut storage as *mut libc::sockaddr_storage as *mut libc::sockaddr,
                &mut len,
            )
        };
        if rc != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(i32::from(storage.ss_family))
    }

    fn set_nonblocking(fd: std::os::fd::RawFd) -> io::Result<()> {
        // Safety: `fd` is owned. F_GETFL/F_SETFL only touch its status flags.
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        let rc = unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

/// The [`Service<Uri>`] handed to tonic. tonic calls it whenever the channel needs a new
/// connection, so a dropped connection is re-established on the next call.
#[derive(Clone)]
pub(crate) struct Connector {
    transport: TransportConfig,
}

impl Connector {
    pub(crate) fn new(transport: TransportConfig) -> Self {
        Connector { transport }
    }
}

impl Service<Uri> for Connector {
    type Response = TokioIo<BoxedStream>;
    type Error = io::Error;
    type Future = Pin<Box<dyn Future<Output = io::Result<Self::Response>> + Send>>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, _uri: Uri) -> Self::Future {
        let transport = self.transport.clone();
        Box::pin(async move { transport.connect().await.map(TokioIo::new) })
    }
}
