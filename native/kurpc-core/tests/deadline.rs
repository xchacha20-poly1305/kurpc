mod common;

use std::time::Duration;

use kurpc_core::{CallOptions, Code, Error};

use common::{ECHO, SLEEP, STATS, Server, assert_status, unary, wait_counter};

#[tokio::test]
async fn timeout_is_deadline_exceeded() {
    let server = Server::tcp().await;
    let mut cancelled = server.stats.sleep_cancelled();
    let options = CallOptions {
        timeout: Some(Duration::from_millis(200)),
        ..CallOptions::default()
    };
    let error = unary(&server.channel(), SLEEP, b"30000", options)
        .await
        .unwrap_err();
    assert_status(error, Code::DeadlineExceeded);
    wait_counter(&mut cancelled, 1).await;
}

#[tokio::test]
async fn cancel_is_observed_by_the_server() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut started = server.stats.sleep_started();
    let mut cancelled = server.stats.sleep_cancelled();
    let (done, result) = tokio::sync::oneshot::channel();
    let handle = channel.unary(
        SLEEP,
        bytes::Bytes::from_static(b"30000"),
        CallOptions::default(),
        move |outcome| {
            let _ = done.send(outcome);
        },
    );
    wait_counter(&mut started, 1).await;
    handle.cancel();
    let error = result.await.unwrap().unwrap_err();
    assert_status(error, Code::Cancelled);
    wait_counter(&mut cancelled, 1).await;

    let stats = unary(&channel, STATS, b"", CallOptions::default())
        .await
        .unwrap();
    let text = std::str::from_utf8(&stats.message).unwrap();
    assert!(text.contains("sleep_started=1\n"), "{text}");
    assert!(text.contains("sleep_cancelled=1\n"), "{text}");
    assert!(text.contains("sleep_finished=0\n"), "{text}");
}

#[tokio::test]
async fn close_during_a_call_is_closed() {
    let server = Server::tcp().await;
    let channel = server.channel();
    let mut started = server.stats.sleep_started();
    let (done, result) = tokio::sync::oneshot::channel();
    channel.unary(
        SLEEP,
        bytes::Bytes::from_static(b"30000"),
        CallOptions::default(),
        move |outcome| {
            let _ = done.send(outcome);
        },
    );
    wait_counter(&mut started, 1).await;
    channel.close();
    let error = result.await.unwrap().unwrap_err();
    assert!(matches!(error, Error::Closed), "{error:?}");
}

#[tokio::test]
async fn close_rejects_a_later_call() {
    let server = Server::tcp().await;
    let channel = server.channel();
    channel.close();
    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Closed), "{error:?}");
}
