#[cfg(unix)]
mod common;

#[cfg(unix)]
use bytes::Bytes;
#[cfg(unix)]
use kurpc_core::{CallOptions, Channel, ChannelConfig, Code, Error, Metadata, TransportConfig};

#[cfg(unix)]
use common::{ECHO, FAIL, Server, unary};

#[cfg(unix)]
#[tokio::test]
async fn echo_round_trip() {
    let server = Server::unix().await;
    let response = unary(&server.channel(), ECHO, b"hello", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "hello");
}

#[cfg(unix)]
#[tokio::test]
async fn empty_message_is_empty_bytes() {
    let server = Server::unix().await;
    let response = unary(&server.channel(), ECHO, b"", CallOptions::default())
        .await
        .unwrap();
    assert!(response.message.is_empty());
}

#[cfg(unix)]
#[tokio::test]
async fn metadata_reaches_server() {
    let server = Server::unix().await;
    let mut config = server.config();
    config.metadata = Metadata::from_iter([("authorization", "Bearer secret")]);
    let channel = Channel::new(config).unwrap();
    let options = CallOptions {
        metadata: Metadata::from_iter([("x-trace-bin", Bytes::from_static(&[0, 1, 255]))]),
        ..CallOptions::default()
    };

    let response = unary(&channel, ECHO, b"", options).await.unwrap();

    let header = |key| {
        response
            .headers
            .iter()
            .find(|(k, _)| *k == key)
            .map(|(_, v)| v.clone())
    };
    assert_eq!(header("authorization").unwrap(), "Bearer secret");
    assert_eq!(
        header("x-trace-bin").unwrap(),
        Bytes::from_static(&[0, 1, 255])
    );
}

#[cfg(unix)]
#[tokio::test]
async fn server_status_is_reported() {
    let server = Server::unix().await;
    let error = unary(
        &server.channel(),
        FAIL,
        b"5:no such thing",
        CallOptions::default(),
    )
    .await
    .unwrap_err();
    match error {
        Error::Status { code, message, .. } => {
            assert_eq!(code, Code::NotFound);
            assert_eq!(message, "no such thing");
        }
        other => panic!("expected a status, got {other:?}"),
    }
}

#[cfg(unix)]
#[tokio::test]
async fn server_unavailable_is_a_status_not_a_transport_error() {
    let server = Server::unix().await;
    let error = unary(
        &server.channel(),
        FAIL,
        b"14:draining",
        CallOptions::default(),
    )
    .await
    .unwrap_err();
    assert!(
        matches!(
            error,
            Error::Status {
                code: Code::Unavailable,
                ..
            }
        ),
        "{error:?}"
    );
}

#[cfg(unix)]
#[tokio::test]
async fn missing_socket_is_a_transport_error() {
    let path = std::env::temp_dir().join("kurpc-core-does-not-exist.sock");
    let channel = Channel::new(ChannelConfig::new(TransportConfig::Unix {
        path: path.to_str().unwrap().to_owned(),
    }))
    .unwrap();
    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Transport { .. }), "{error:?}");
}

#[cfg(unix)]
#[tokio::test]
async fn closed_channel_rejects_calls() {
    let server = Server::unix().await;
    let channel = server.channel();
    channel.close();
    let error = unary(&channel, ECHO, b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Closed), "{error:?}");
}

#[cfg(unix)]
#[tokio::test]
async fn malformed_method_is_rejected() {
    let server = Server::unix().await;
    let error = unary(&server.channel(), "Echo", b"", CallOptions::default())
        .await
        .unwrap_err();
    assert!(matches!(error, Error::InvalidArgument { .. }), "{error:?}");
}
