use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};
use std::time::Duration;

use bytes::Bytes;
use tokio::sync::{Notify, Semaphore, mpsc};
use tokio_stream::Stream;

use crate::codec::QueuedRequest;
use crate::{Metadata, runtime};

/// Request messages queued ahead of the HTTP/2 stream. With one slot, a send completes once the
/// previous message has been taken by the transport, so a fast producer waits on flow control
/// instead of filling memory.
const REQUEST_QUEUE_CAPACITY: usize = 1;

/// Messages the server stream may deliver before [`CallHandle::request`].
/// Matches the JNI `initialDemand` default (plan §5.3). Unary calls ignore it.
pub const DEFAULT_INITIAL_DEMAND: u32 = 16;

#[derive(Debug, Clone)]
pub struct CallOptions {
    /// Sent as `grpc-timeout` and enforced locally; the call fails with `DeadlineExceeded`.
    pub timeout: Option<Duration>,
    /// Appended after the channel's own metadata.
    pub metadata: Metadata,
    /// Retry connector failures with backoff until the deadline, cancellation, or close.
    /// Failures after the request was sent are not retried. Default `false` (plan §3.4);
    /// the Kotlin default is `true`.
    pub wait_for_ready: bool,
    /// How many stream messages may be delivered before [`CallHandle::request`].
    /// `0` delivers nothing until `request`. While demand is zero the task holds at
    /// most one already-read message and does not pull another. Ignored by unary calls.
    pub initial_demand: u32,
}

impl Default for CallOptions {
    fn default() -> Self {
        CallOptions {
            timeout: None,
            metadata: Metadata::default(),
            wait_for_ready: false,
            initial_demand: DEFAULT_INITIAL_DEMAND,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Response {
    /// The message body, possibly empty: a protobuf message with only default values encodes to
    /// zero bytes.
    pub message: Bytes,
    pub headers: Metadata,
    pub trailers: Metadata,
}

/// Receives one server stream.
///
/// `on_headers` runs at most once, before any message. `on_message` runs once per delivered
/// message. `on_complete` runs exactly once after that, including when the call is cancelled,
/// hits its deadline, or the channel closes. None of the methods may block: they run on a
/// runtime worker.
pub trait StreamSink: Send + 'static {
    fn on_headers(&mut self, headers: Metadata) {
        let _ = headers;
    }

    fn on_message(&mut self, message: Bytes);

    /// `Ok` is the trailing metadata of a stream that ended with status OK.
    fn on_complete(&mut self, result: crate::Result<Metadata>);
}

/// Controls a running call. Dropping the handle does not cancel the call.
#[derive(Debug, Clone)]
pub struct CallHandle {
    cancel: Arc<Notify>,
    demand: Arc<Semaphore>,
    /// The request side of a client-streaming or bidi call; `None` for single-request calls.
    /// Taken by [`close_send`](Self::close_send).
    requests: Option<Arc<Mutex<Option<mpsc::Sender<Bytes>>>>>,
}

impl CallHandle {
    pub(crate) fn new(initial_demand: u32) -> Self {
        CallHandle {
            cancel: Arc::new(Notify::new()),
            demand: Arc::new(Semaphore::new(initial_demand as usize)),
            requests: None,
        }
    }

    /// A handle that sends request messages, and the queue the call reads them from.
    pub(crate) fn with_requests(initial_demand: u32) -> (Self, RequestQueue) {
        let (sender, receiver) = mpsc::channel(REQUEST_QUEUE_CAPACITY);
        let handle = CallHandle {
            requests: Some(Arc::new(Mutex::new(Some(sender)))),
            ..Self::new(initial_demand)
        };
        let requests = RequestQueue {
            receiver: Arc::new(Mutex::new(receiver)),
            abort: Arc::default(),
        };
        (handle, requests)
    }

    /// Queues one request message of a client-streaming or bidi call. `done` runs once on a
    /// runtime worker: `true` when the message was queued, `false` when the call takes no more
    /// requests (it has ended, [`close_send`](Self::close_send) ran, or it has a single request).
    /// Never blocks the caller. Send the next message only after `done`, to keep the order.
    pub fn send(&self, message: Bytes, done: impl FnOnce(bool) + Send + 'static) {
        let sender = self
            .requests
            .as_ref()
            .and_then(|requests| lock(requests).clone());
        runtime::get().spawn(async move {
            let queued = match sender {
                Some(sender) => sender.send(message).await.is_ok(),
                None => false,
            };
            done(queued);
        });
    }

    /// Ends the request stream (half-close). Messages already queued are still sent.
    /// Idempotent; does nothing for a single-request call.
    pub fn close_send(&self) {
        if let Some(requests) = &self.requests {
            lock(requests).take();
        }
    }

    /// Cancels the call. The call's callback still runs, with `Cancelled`, unless the call has
    /// already finished. The HTTP/2 stream is reset with `CANCEL`.
    pub fn cancel(&self) {
        // `notify_one` stores a permit, so a cancel that lands before the call starts waiting
        // is not lost.
        self.cancel.notify_one();
    }

    /// Allows `n` more messages. `0` does nothing. Permits past
    /// [`Semaphore::MAX_PERMITS`] are ignored.
    pub fn request(&self, n: u32) {
        if n == 0 {
            return;
        }
        // `available_permits` can move under us, but only downward (the call task is the only
        // consumer). A room computed from a higher number is still safe to add.
        let room = Semaphore::MAX_PERMITS.saturating_sub(self.demand.available_permits());
        let n = (n as usize).min(room);
        if n > 0 {
            self.demand.add_permits(n);
        }
    }

    pub(crate) async fn cancelled(&self) {
        self.cancel.notified().await;
    }

    /// Waits for one permit and consumes it.
    pub(crate) async fn acquire(&self) {
        self.demand
            .acquire()
            .await
            .expect("demand semaphore is never closed")
            .forget();
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poison| poison.into_inner())
}

/// The request stream of a client-streaming or bidi call, as tonic's request body.
///
/// Clones share one receiver. A wait-for-ready retry builds a new body from a clone: a connect
/// failure happens before the body is polled, so no message is lost between attempts.
#[derive(Debug, Clone)]
pub(crate) struct RequestQueue {
    receiver: Arc<Mutex<mpsc::Receiver<Bytes>>>,
    abort: Arc<AbortSignal>,
}

impl RequestQueue {
    /// Makes the body fail, which resets the HTTP/2 stream with `CANCEL` (see
    /// [`RequestStreamCodec`](crate::codec::RequestStreamCodec)), and later sends fail. Called
    /// when the call ends; a body that already ended is not polled again, so this does nothing
    /// to it.
    pub(crate) fn abort(&self) {
        self.abort.aborted.store(true, Ordering::Release);
        if let Some(waker) = lock(&self.abort.waker).take() {
            waker.wake();
        }
    }
}

#[derive(Debug, Default)]
struct AbortSignal {
    aborted: AtomicBool,
    waker: Mutex<Option<Waker>>,
}

impl Stream for RequestQueue {
    type Item = QueuedRequest;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<QueuedRequest>> {
        // Register before checking, so an abort between the two still wakes this task.
        *lock(&self.abort.waker) = Some(cx.waker().clone());
        if self.abort.aborted.load(Ordering::Acquire) {
            return Poll::Ready(Some(QueuedRequest::Abort));
        }
        lock(&self.receiver)
            .poll_recv(cx)
            .map(|message| message.map(QueuedRequest::Message))
    }
}
