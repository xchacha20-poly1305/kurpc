use bytes::{Buf, BufMut, Bytes};
use tonic::Status;
use tonic::codec::{Codec, DecodeBuf, Decoder, EncodeBuf, Encoder};

/// A [`Codec`] that moves message bodies through unchanged.
///
/// The content type stays `application/grpc`, so servers keep using their generated stubs.
#[derive(Debug, Clone, Copy, Default)]
pub struct PassthroughCodec;

impl Codec for PassthroughCodec {
    type Encode = Bytes;
    type Decode = Bytes;
    type Encoder = PassthroughCodec;
    type Decoder = PassthroughCodec;

    fn encoder(&mut self) -> Self::Encoder {
        PassthroughCodec
    }

    fn decoder(&mut self) -> Self::Decoder {
        PassthroughCodec
    }
}

impl Encoder for PassthroughCodec {
    type Item = Bytes;
    type Error = Status;

    fn encode(&mut self, item: Bytes, dst: &mut EncodeBuf<'_>) -> Result<(), Status> {
        dst.put(item);
        Ok(())
    }
}

impl Decoder for PassthroughCodec {
    type Item = Bytes;
    type Error = Status;

    fn decode(&mut self, src: &mut DecodeBuf<'_>) -> Result<Option<Bytes>, Status> {
        // `src` holds exactly one framed message; `copy_to_bytes` splits it off without copying.
        Ok(Some(src.copy_to_bytes(src.remaining())))
    }
}

/// An item of a client-streaming or bidi request body.
#[derive(Debug)]
pub(crate) enum QueuedRequest {
    Message(Bytes),
    /// The call has ended while its request stream was still open.
    Abort,
}

/// [`PassthroughCodec`] for streaming request bodies.
///
/// When the response finishes first (the server sent its status while requests were still
/// open), hyper keeps piping the body, so the HTTP/2 stream would stay half open until every
/// sender is dropped. Like grpc-go, kurpc resets it with `CANCEL` instead: tonic turns an encoder
/// error into a body error, and hyper resets the stream with the `h2::Reason` found in that
/// error's source chain. (A call cancelled before its response is reset by hyper itself.)
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct RequestStreamCodec;

impl Codec for RequestStreamCodec {
    type Encode = QueuedRequest;
    type Decode = Bytes;
    type Encoder = RequestStreamCodec;
    type Decoder = PassthroughCodec;

    fn encoder(&mut self) -> Self::Encoder {
        RequestStreamCodec
    }

    fn decoder(&mut self) -> Self::Decoder {
        PassthroughCodec
    }
}

impl Encoder for RequestStreamCodec {
    type Item = QueuedRequest;
    type Error = Status;

    fn encode(&mut self, item: QueuedRequest, dst: &mut EncodeBuf<'_>) -> Result<(), Status> {
        match item {
            QueuedRequest::Message(message) => {
                dst.put(message);
                Ok(())
            }
            QueuedRequest::Abort => Err(Status::from_error(Box::new(h2::Error::from(
                h2::Reason::CANCEL,
            )))),
        }
    }
}
