use std::ffi::c_void;
use std::ptr;
use std::sync::Arc;

use jni::JavaVM;
use jni::sys::{self, JNI_VERSION_1_6, jint};

/// ART accepts only 1.2, 1.4 and 1.6 (`IsBadJniVersion`), both from `JNI_OnLoad` and in attach
/// arguments; HotSpot accepts 1.6 too. Nothing kurpc calls is newer than 1.6.
const JNI_VERSION: jint = JNI_VERSION_1_6;

/// # Safety
/// Called by the JVM, with a valid `vm`, when it loads the library.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut sys::JavaVM, _reserved: *mut c_void) -> jint {
    unsafe { JavaVM::from_raw(vm) };
    // Fails only if the runtime already started, which cannot happen before the library loads.
    let _ = kurpc_core::runtime::configure(|config| {
        config.on_thread_start = Some(Arc::new(attach_as_daemon));
        config.on_thread_stop = Some(Arc::new(detach));
    });
    JNI_VERSION
}

/// Attaches a runtime worker once for its whole life, so callbacks skip per-call attachment.
/// Daemon threads do not keep the JVM from exiting.
fn attach_as_daemon() {
    let Ok(vm) = JavaVM::singleton() else { return };
    let mut args = sys::JavaVMAttachArgs {
        version: JNI_VERSION,
        name: c"kurpc-worker".as_ptr().cast_mut(),
        group: ptr::null_mut(),
    };
    let mut env = ptr::null_mut();
    // Safety: `vm` is valid for the life of the process, and `args` outlives the call.
    // Failure leaves the thread detached; jni-rs then attaches it on first use instead.
    unsafe {
        let raw = vm.get_raw();
        ((**raw).v1_4.AttachCurrentThreadAsDaemon)(raw, &mut env, (&raw mut args).cast());
    }
}

fn detach() {
    let Ok(vm) = JavaVM::singleton() else { return };
    // Safety: runs as the worker exits, after its last JNI frame has returned.
    unsafe {
        let raw = vm.get_raw();
        ((**raw).v1_1.DetachCurrentThread)(raw);
    }
}
