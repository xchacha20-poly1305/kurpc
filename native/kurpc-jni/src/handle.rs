//! Native objects handed to Kotlin as `Long` handles.
//!
//! A handle is the address of a `Box<T>`. Kotlin owns it and must release it exactly once, after
//! which it never passes the handle again; its handle wrappers enforce this.
//!
//! [`LIVE`] counts handles still owned by Kotlin. The leak check (plan §7) reads it after every
//! channel is closed and every call callback has run. Relaxed ordering is enough: the counters
//! do not publish the handles themselves.

use std::sync::atomic::{AtomicUsize, Ordering};

use jni::sys::jlong;

use crate::error::{BridgeError, Result};

pub(crate) struct LiveHandles {
    pub channels: AtomicUsize,
    pub calls: AtomicUsize,
    pub dials: AtomicUsize,
    pub pipes: AtomicUsize,
}

pub(crate) static LIVE: LiveHandles = LiveHandles {
    channels: AtomicUsize::new(0),
    calls: AtomicUsize::new(0),
    dials: AtomicUsize::new(0),
    pipes: AtomicUsize::new(0),
};

impl LiveHandles {
    /// `[channels, calls, dials, pipes]`.
    pub(crate) fn snapshot(&self) -> [usize; 4] {
        [&self.channels, &self.calls, &self.dials, &self.pipes]
            .map(|counter| counter.load(Ordering::Relaxed))
    }
}

pub(crate) fn into_handle<T>(value: T, counter: &AtomicUsize) -> jlong {
    counter.fetch_add(1, Ordering::Relaxed);
    Box::into_raw(Box::new(value)) as jlong
}

/// # Safety
/// `handle` must come from [`into_handle::<T>`] and not have been released.
pub(crate) unsafe fn borrow<'a, T>(handle: jlong) -> Result<&'a T> {
    let pointer = non_null::<T>(handle)?;
    Ok(unsafe { &*pointer })
}

/// # Safety
/// `handle` must come from [`into_handle::<T>`] and not have been released.
pub(crate) unsafe fn release<T>(handle: jlong, counter: &AtomicUsize) -> Result<T> {
    let pointer = non_null::<T>(handle)?;
    let value = *unsafe { Box::from_raw(pointer) };
    counter.fetch_sub(1, Ordering::Relaxed);
    Ok(value)
}

fn non_null<T>(handle: jlong) -> Result<*mut T> {
    if handle == 0 {
        return Err(BridgeError::InvalidArgument(
            "null native handle".to_owned(),
        ));
    }
    Ok(handle as *mut T)
}
