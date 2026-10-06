mod common;

use kurpc_core::{CallOptions, Channel};

use common::{SCHEME, Server, unary};

#[tokio::test]
async fn plain_channel_sends_http_scheme() {
    let server = Server::tcp().await;
    let response = unary(&server.channel(), SCHEME, b"", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "http");
}

/// The server is plaintext, so this also shows kurpc never starts TLS itself, even though the
/// test build enables tonic's TLS through `kurpc-testserver`.
#[tokio::test]
async fn secure_channel_sends_https_scheme_without_its_own_tls() {
    let server = Server::tcp().await;
    let mut config = server.config();
    config.secure = true;
    let channel = Channel::new(config).unwrap();
    let response = unary(&channel, SCHEME, b"", CallOptions::default())
        .await
        .unwrap();
    assert_eq!(response.message, "https");
}
