#[cfg(unix)]
mod common;

#[cfg(unix)]
use std::path::PathBuf;

#[cfg(unix)]
use kurpc_core::CallOptions;

#[cfg(unix)]
use common::{ECHO, Server, unary};

#[cfg(unix)]
#[tokio::test]
async fn the_channel_reconnects_after_the_server_restarts() {
    let server = Server::unix().await;
    let path = PathBuf::from(server.unix_path());
    let channel = server.channel();
    let first = unary(&channel, ECHO, b"one", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(first.message, "one");

    server.shutdown().await;
    let _restarted = Server::unix_at(path).await;

    let mut last = unary(&channel, ECHO, b"two", CallOptions::default()).await;
    for _ in 0..4 {
        if last.is_ok() {
            break;
        }
        last = unary(&channel, ECHO, b"two", CallOptions::default()).await;
    }
    match last {
        Ok(response) => assert_eq!(response.message, "two"),
        Err(error) => panic!("channel did not reconnect: {error:?}"),
    }
}
