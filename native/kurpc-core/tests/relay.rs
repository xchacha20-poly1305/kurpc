mod common;

use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;

use kurpc_core::relay::{DialRequest, Pipe, RequestDialer};
use kurpc_core::{CallOptions, Channel, ChannelConfig, Error, TransportConfig};

use common::{COUNT, ECHO, Server, server_streaming, stream_result, unary};

/// Answers each dial from a plain thread with a blocking TCP socket, pumped by two more
/// threads, the way the JNI binding pumps a Kotlin `Connection`.
fn tcp_relay(port: u16, dials: Arc<AtomicUsize>) -> ChannelConfig {
    let dialer = RequestDialer::new(move |request: DialRequest| {
        dials.fetch_add(1, Ordering::SeqCst);
        thread::spawn(move || match TcpStream::connect(("127.0.0.1", port)) {
            Ok(socket) => {
                if let Some(pipe) = request.stream() {
                    pump(Arc::new(pipe), socket);
                }
            }
            Err(error) => request.fail(error.to_string()),
        });
    });
    ChannelConfig::new(TransportConfig::Custom(Arc::new(dialer)))
}

fn pump(pipe: Arc<Pipe>, socket: TcpStream) {
    let mut inbound_socket = socket.try_clone().unwrap();
    let inbound_pipe = pipe.clone();
    let inbound = thread::spawn(move || {
        let mut buffer = [0u8; 16 * 1024];
        loop {
            match inbound_socket.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(read) => {
                    if inbound_pipe.write_blocking(&buffer[..read]).is_err() {
                        break;
                    }
                }
            }
        }
        inbound_pipe.shutdown_blocking();
    });
    let mut outbound_socket = socket;
    while let Some(Ok(())) = pipe.read_blocking(16 * 1024, |bytes| outbound_socket.write_all(bytes))
    {
    }
    // Unblocks the inbound read, the way the binding closes the user's connection.
    let _ = outbound_socket.shutdown(Shutdown::Both);
    inbound.join().unwrap();
}

#[tokio::test]
async fn relayed_unary_and_stream_round_trip() {
    let server = Server::tcp().await;
    let dials = Arc::new(AtomicUsize::new(0));
    let channel = Channel::new(tcp_relay(server.port(), dials.clone())).unwrap();

    let response = unary(&channel, ECHO, b"relay", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "relay");

    let stream = server_streaming(&channel, COUNT, b"10", CallOptions::default());
    stream_result(stream.done).await.unwrap();
    assert_eq!(stream.messages.lock().unwrap().len(), 10);
    assert_eq!(dials.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn relayed_connection_is_redialed_after_server_restart() {
    let server = Server::tcp().await;
    let port = server.port();
    let dials = Arc::new(AtomicUsize::new(0));
    let channel = Channel::new(tcp_relay(port, dials.clone())).unwrap();
    unary(&channel, ECHO, b"one", CallOptions::default())
        .await
        .unwrap();

    server.shutdown().await;
    let _server = Server::tcp_on(port).await;
    let options = CallOptions {
        wait_for_ready: true,
        timeout: Some(Duration::from_secs(5)),
        ..CallOptions::default()
    };
    let response = unary(&channel, ECHO, b"two", options).await.unwrap();
    assert_eq!(response.message, "two");
    assert!(dials.load(Ordering::SeqCst) >= 2);
}

#[tokio::test]
async fn failed_dial_is_a_transport_error_with_the_binding_message() {
    let dialer = RequestDialer::new(|request: DialRequest| {
        request.fail("connector refused".to_owned());
    });
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(Arc::new(
        dialer,
    ))))
    .unwrap();
    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    let Error::Transport { message } = error else {
        panic!("expected a transport error, got {error:?}");
    };
    assert!(message.contains("connector refused"), "{message}");
}

#[tokio::test]
async fn dropped_dial_request_fails_the_dial() {
    let dialer = RequestDialer::new(drop::<DialRequest>);
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(Arc::new(
        dialer,
    ))))
    .unwrap();
    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Transport { .. }), "{error:?}");
}

#[tokio::test]
async fn dial_abandoned_by_connect_timeout_returns_no_pipe() {
    let (requests, pending) = mpsc::channel::<DialRequest>();
    let dialer = RequestDialer::new(move |request| requests.send(request).unwrap());
    let mut config = ChannelConfig::new(TransportConfig::Custom(Arc::new(dialer)));
    config.connect_timeout = Duration::from_millis(100);
    let channel = Channel::new(config).unwrap();

    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Transport { .. }), "{error:?}");
    let request = pending.recv_timeout(Duration::from_secs(1)).unwrap();
    assert!(request.stream().is_none());
}

#[tokio::test]
async fn binding_eof_ends_the_connection() {
    let (pipes, pipe) = mpsc::channel::<Pipe>();
    let dialer = RequestDialer::new(move |request: DialRequest| {
        pipes.send(request.stream().unwrap()).unwrap();
    });
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Custom(Arc::new(
        dialer,
    ))))
    .unwrap();
    let call = unary(&channel, ECHO, b"", CallOptions::default());

    let reader = thread::spawn(move || {
        let pipe = pipe.recv_timeout(Duration::from_secs(3)).unwrap();
        // The client preface arrives first; then the binding reports EOF as a dead connection.
        assert!(pipe.read_blocking(1024, |bytes| bytes.len()).is_some());
        pipe.shutdown_blocking();
        while pipe.read_blocking(1024, |_| ()).is_some() {}
    });
    let error = call.await.unwrap_err();
    assert!(matches!(error, Error::Transport { .. }), "{error:?}");
    reader.join().unwrap();
}
