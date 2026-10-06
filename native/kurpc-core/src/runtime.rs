//! The process-wide tokio runtime that drives every channel and call.
//!
//! The runtime starts on first use. Until then [`configure`] may adjust it; binding layers use the
//! thread hooks to register worker threads with their host VM once instead of on every callback.

use std::sync::{Arc, Mutex, OnceLock};

use tokio::runtime::{Builder, Runtime};

pub type ThreadHook = Arc<dyn Fn() + Send + Sync>;

#[derive(Clone)]
pub struct RuntimeConfig {
    pub worker_threads: usize,
    pub on_thread_start: Option<ThreadHook>,
    pub on_thread_stop: Option<ThreadHook>,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        RuntimeConfig {
            worker_threads: 2,
            on_thread_start: None,
            on_thread_stop: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AlreadyStarted;

/// Configuration not yet consumed by a start. `started` lives under the same lock as the
/// configuration so an edit can never slip in after a start has read it.
struct Pending {
    config: Option<RuntimeConfig>,
    started: bool,
}

static PENDING: Mutex<Pending> = Mutex::new(Pending {
    config: None,
    started: false,
});
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// Edits the configuration the runtime will start with. Fails once the runtime has started.
pub fn configure(edit: impl FnOnce(&mut RuntimeConfig)) -> Result<(), AlreadyStarted> {
    let mut pending = PENDING.lock().unwrap_or_else(|e| e.into_inner());
    if pending.started {
        return Err(AlreadyStarted);
    }
    edit(pending.config.get_or_insert_with(RuntimeConfig::default));
    Ok(())
}

pub fn get() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        let config = {
            let mut pending = PENDING.lock().unwrap_or_else(|e| e.into_inner());
            pending.started = true;
            pending.config.take().unwrap_or_default()
        };
        build(config)
    })
}

fn build(config: RuntimeConfig) -> Runtime {
    let mut builder = Builder::new_multi_thread();
    builder
        .worker_threads(config.worker_threads.max(1))
        .thread_name("kurpc-worker")
        .enable_all();
    if let Some(hook) = config.on_thread_start {
        builder.on_thread_start(move || hook());
    }
    if let Some(hook) = config.on_thread_stop {
        builder.on_thread_stop(move || hook());
    }
    builder.build().expect("failed to start the kurpc runtime")
}
