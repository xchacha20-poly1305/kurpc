//! A custom transport whose bytes come from the other side of an FFI boundary (plan §5.4).
//!
//! [`RequestDialer`] turns each connection tonic needs into a [`DialRequest`] handed to the
//! binding. The binding answers it once: with a [`Pipe`] that it pumps from its own thread, with
//! an already-connected socket, or with a failure. Pumping is blocking on purpose: the binding
//! runs each direction on a thread it owns (Kotlin's `Dispatchers.IO`), and a blocking read or
//! write that returns when bytes move is the cheapest way to carry back-pressure across JNI.

use std::future::Future;
use std::io;
#[cfg(unix)]
use std::os::fd::OwnedFd;
use std::pin::Pin;
use std::sync::Mutex;

use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream, ReadHalf, WriteHalf};
use tokio::sync::oneshot;

use crate::runtime;
use crate::transport::{CustomDialer, Dialed};

/// Bytes buffered in each direction between tonic and the binding's pumps.
const PIPE_CAPACITY: usize = 64 * 1024;

/// One pending connection. Dropping it unanswered fails the dial.
pub struct DialRequest {
    reply: oneshot::Sender<io::Result<Dialed>>,
}

impl DialRequest {
    /// Connects tonic to a new pipe. `None` means the dial was abandoned (connect timeout or
    /// channel closed) while the binding was connecting; it should close its connection.
    pub fn stream(self) -> Option<Pipe> {
        let (tonic_end, pump_end) = tokio::io::duplex(PIPE_CAPACITY);
        self.reply
            .send(Ok(Dialed::Stream(Box::new(tonic_end))))
            .ok()?;
        let (reader, writer) = tokio::io::split(pump_end);
        Some(Pipe {
            reader: Mutex::new(Reader {
                half: reader,
                scratch: Vec::new(),
            }),
            writer: Mutex::new(writer),
        })
    }

    /// Hands tonic a connected socket. If the dial was abandoned the socket is closed here.
    #[cfg(unix)]
    pub fn fd(self, fd: OwnedFd) {
        let _ = self.reply.send(Ok(Dialed::Fd(fd)));
    }

    /// Fails the dial; calls waiting on it see [`Error::Transport`](crate::Error::Transport)
    /// carrying `message`.
    pub fn fail(self, message: String) {
        let _ = self.reply.send(Err(io::Error::other(message)));
    }
}

/// A [`CustomDialer`] that hands every dial to `request` and waits for the answer.
///
/// `request` runs on a runtime worker and must return promptly; the binding answers later from
/// its own thread. tonic's connect timeout bounds the wait.
pub struct RequestDialer<F> {
    request: F,
}

impl<F: Fn(DialRequest) + Send + Sync> RequestDialer<F> {
    pub fn new(request: F) -> Self {
        RequestDialer { request }
    }
}

impl<F: Fn(DialRequest) + Send + Sync> CustomDialer for RequestDialer<F> {
    fn dial(&self) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + '_>> {
        let (reply, answer) = oneshot::channel();
        (self.request)(DialRequest { reply });
        Box::pin(async move {
            answer
                .await
                .unwrap_or_else(|_| Err(io::Error::other("dial request dropped unanswered")))
        })
    }
}

struct Reader {
    half: ReadHalf<DuplexStream>,
    /// Reused across reads so a pump does not allocate per chunk.
    scratch: Vec<u8>,
}

/// The binding's end of a connection. Each direction is meant for one pumping thread; the
/// mutexes only make the type `Sync`.
///
/// The methods block on the runtime and must not be called from a runtime worker.
pub struct Pipe {
    reader: Mutex<Reader>,
    writer: Mutex<WriteHalf<DuplexStream>>,
}

impl Pipe {
    /// Waits for bytes tonic wrote, at most `max`, and passes them to `deliver`. `None` is end of
    /// stream: tonic dropped the connection.
    pub fn read_blocking<R>(&self, max: usize, deliver: impl FnOnce(&[u8]) -> R) -> Option<R> {
        let mut reader = self
            .reader
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let Reader { half, scratch } = &mut *reader;
        scratch.resize(max, 0);
        // A duplex read only ends with `Ok`; zero bytes is the dropped peer.
        let read = runtime::get().block_on(half.read(scratch)).unwrap_or(0);
        if read == 0 && max > 0 {
            return None;
        }
        Some(deliver(&scratch[..read]))
    }

    /// Writes all of `bytes` toward tonic, blocking while its buffer is full. Fails once tonic
    /// dropped the connection.
    pub fn write_blocking(&self, bytes: &[u8]) -> io::Result<()> {
        let mut writer = self
            .writer
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        runtime::get().block_on(writer.write_all(bytes))
    }

    /// End of stream toward tonic, after the binding's connection reached EOF or failed. tonic
    /// then drops the connection, which ends [`read_blocking`](Self::read_blocking) too.
    pub fn shutdown_blocking(&self) {
        let mut writer = self
            .writer
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let _ = runtime::get().block_on(writer.shutdown());
    }
}
