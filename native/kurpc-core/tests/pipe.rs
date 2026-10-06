//! Named-pipe transport. The module is empty on non-Windows targets.

#![cfg(windows)]

mod common;

use kurpc_core::{CallOptions, Channel, ChannelConfig, TransportConfig};

use common::{ECHO, unary};

#[tokio::test]
async fn named_pipe_round_trip() {
    let name = format!(r"\\.\pipe\kurpc-core-{}", std::process::id());
    let listener = kurpc_testserver::PipeListener::bind(&name).expect("bind pipe");
    let service = kurpc_testserver::TestService::new();
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    let task = tokio::spawn(async move {
        kurpc_testserver::serve(
            kurpc_testserver::Listener::NamedPipe(listener),
            service,
            None,
            async move {
                let _ = stopped.await;
            },
        )
        .await
    });

    let channel = Channel::new(ChannelConfig::new(TransportConfig::WindowsNamedPipe {
        name,
    }))
    .unwrap();
    let response = unary(&channel, ECHO, b"pipe", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message.as_ref(), b"pipe");

    drop(stop);
    task.await.unwrap().unwrap();
}
