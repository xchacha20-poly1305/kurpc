mod common;

use std::time::Duration;

use kurpc_core::{CallOptions, Channel, ChannelConfig, Code, TransportConfig};

use common::{
    CHAT, COLLECT, ECHO, Server, assert_status, bidi_streaming, call_result, client_streaming,
    send, stream_result, unary, wait_counter,
};

#[tokio::test]
async fn client_stream_collects_every_message_after_half_close() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let (call, result) = client_streaming(&channel, COLLECT, CallOptions::default());
    for message in ["a", "bb", "ccc"] {
        assert!(send(&call, message).await);
    }
    call.close_send();
    let response = call_result(result).await.unwrap();
    assert_eq!(response.message, "3:6");
}

#[tokio::test]
async fn client_stream_with_no_messages_is_a_valid_call() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let (call, result) = client_streaming(&channel, COLLECT, CallOptions::default());
    call.close_send();
    assert_eq!(call_result(result).await.unwrap().message, "0:0");
}

#[tokio::test]
async fn server_failure_ends_a_client_stream_and_refuses_later_sends() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let (call, result) = client_streaming(&channel, COLLECT, CallOptions::default());
    assert!(send(&call, "a").await);
    assert!(send(&call, "fail").await);
    let message = assert_status(
        call_result(result).await.unwrap_err(),
        Code::InvalidArgument,
    );
    assert!(message.contains("fail requested"), "{message}");
    // The call is over, so its request queue is gone.
    assert!(!send(&call, "late").await);
}

#[tokio::test]
async fn cancelling_a_client_stream_ends_the_server_handler() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut received = server.stats.requests_received();
    // Never half-closed: without the reset, the handler would wait for more requests forever.
    let mut ended = server.stats.collect_ended();
    let (call, result) = client_streaming(&channel, COLLECT, CallOptions::default());
    assert!(send(&call, "a").await);
    wait_counter(&mut received, 1).await;
    call.cancel();
    assert_status(call_result(result).await.unwrap_err(), Code::Cancelled);
    wait_counter(&mut ended, 1).await;
}

#[tokio::test]
async fn bidi_echoes_each_message_and_ends_on_half_close() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut stream = bidi_streaming(&channel, CHAT, CallOptions::default());
    for (index, message) in ["one", "two", "three"].into_iter().enumerate() {
        assert!(send(&stream.handle, message).await);
        // Each echo arrives before the next send: the directions are independent.
        wait_counter(&mut stream.received, index as u64 + 1).await;
    }
    stream.handle.close_send();
    stream_result(stream.done).await.unwrap();
    let messages = stream.messages.lock().unwrap().clone();
    assert_eq!(messages, ["one", "two", "three"]);
}

#[tokio::test]
async fn server_ending_a_bidi_call_refuses_later_sends() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let stream = bidi_streaming(&channel, CHAT, CallOptions::default());
    assert!(send(&stream.handle, "hi").await);
    assert!(send(&stream.handle, "bye").await);
    stream_result(stream.done).await.unwrap();
    assert!(!send(&stream.handle, "late").await);
    assert_eq!(stream.messages.lock().unwrap().clone(), ["hi"]);
}

#[tokio::test]
async fn cancelling_a_bidi_call_reaches_the_server() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut cancelled = server.stats.chat_cancelled();
    let mut stream = bidi_streaming(&channel, CHAT, CallOptions::default());
    assert!(send(&stream.handle, "hi").await);
    wait_counter(&mut stream.received, 1).await;
    stream.handle.cancel();
    assert_status(
        stream_result(stream.done).await.unwrap_err(),
        Code::Cancelled,
    );
    wait_counter(&mut cancelled, 1).await;
}

#[tokio::test]
async fn single_request_calls_refuse_sends() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let stream = common::server_streaming(&channel, common::COUNT, b"1", CallOptions::default());
    assert!(!send(&stream.handle, "extra").await);
    stream_result(stream.done).await.unwrap();
    unary(&channel, ECHO, b"still fine", CallOptions::default())
        .await
        .unwrap();
}

#[tokio::test]
async fn queued_requests_survive_wait_for_ready_retries() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Tcp {
        host: "127.0.0.1".to_owned(),
        port,
    }))
    .unwrap();
    let options = CallOptions {
        wait_for_ready: true,
        timeout: Some(Duration::from_secs(5)),
        ..CallOptions::default()
    };
    let (call, result) = client_streaming(&channel, COLLECT, options);
    // Fits in the queue while every attempt is still being refused.
    assert!(send(&call, "early").await);

    let _server = Server::tcp_on(port).await;
    assert!(send(&call, "late").await);
    call.close_send();
    assert_eq!(call_result(result).await.unwrap().message, "2:9");
}

#[tokio::test]
async fn closing_the_channel_ends_a_bidi_call_and_resets_it() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut cancelled = server.stats.chat_cancelled();
    let mut stream = bidi_streaming(&channel, CHAT, CallOptions::default());
    assert!(send(&stream.handle, "hi").await);
    wait_counter(&mut stream.received, 1).await;
    channel.close();
    let error = stream_result(stream.done).await.unwrap_err();
    assert!(matches!(error, kurpc_core::Error::Closed), "{error:?}");
    wait_counter(&mut cancelled, 1).await;
}
