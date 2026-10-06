mod common;

use std::time::Duration;

use kurpc_core::{CallOptions, Code};

use common::{
    COUNT, INFINITE, Server, message_index, server_streaming, stream_result, wait_counter,
    wait_quiescent,
};

#[tokio::test]
async fn count_delivers_every_message_then_ok() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let live = server_streaming(&channel, COUNT, b"4", CallOptions::default());
    // Success is the `Ok` trailers. Tonic keeps ordinary HTTP trailers (content-type,
    // date); an empty map is not part of the contract.
    stream_result(live.done).await.unwrap();
    let messages = live.messages.lock().unwrap();
    assert_eq!(messages.len(), 4);
    for (index, message) in messages.iter().enumerate() {
        assert_eq!(message_index(message), index as u64);
        assert_eq!(message.len(), 64 * 1024);
    }
    assert_eq!(*live.headers.borrow(), 1);
}

#[tokio::test]
async fn count_errors_after_k_messages() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let live = server_streaming(&channel, COUNT, b"10:3", CallOptions::default());
    let error = stream_result(live.done).await.unwrap_err();
    let message = common::assert_status(error, Code::Aborted);
    assert!(message.contains('3'), "{message}");
    let messages = live.messages.lock().unwrap();
    assert_eq!(messages.len(), 3);
    assert_eq!(*live.headers.borrow(), 1);
}

#[tokio::test]
async fn infinite_cancel_is_observed_by_the_server() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut sent = server.stats.stream_sent();
    let mut cancelled = server.stats.stream_cancelled();
    let mut live = server_streaming(&channel, INFINITE, b"", CallOptions::default());
    wait_counter(&mut live.received, 1).await;
    wait_counter(&mut sent, 1).await;
    live.handle.cancel();
    let error = stream_result(live.done).await.unwrap_err();
    common::assert_status(error, Code::Cancelled);
    wait_counter(&mut cancelled, 1).await;
}

#[tokio::test]
async fn demand_stalls_the_server_until_more_is_requested() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut sent = server.stats.stream_sent();
    let options = CallOptions {
        initial_demand: 1,
        ..CallOptions::default()
    };
    let mut live = server_streaming(&channel, COUNT, b"120", options);
    wait_counter(&mut live.received, 1).await;
    // One message was delivered. Demand is now zero, so the task holds at most the next
    // message and stops reading. The server can still fill the HTTP/2 window; a quiet
    // stretch means it stopped being pulled.
    let stalled = wait_quiescent(&mut sent, Duration::from_millis(200)).await;
    assert!(
        stalled < 60,
        "server sent {stalled} messages with the client holding demand at 1"
    );
    assert_eq!(*live.received.borrow(), 1);
    live.handle.request(120);
    wait_counter(&mut live.received, 120).await;
    stream_result(live.done).await.unwrap();
}
