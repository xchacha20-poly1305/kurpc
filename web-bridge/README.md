# Browser bridge

`src/index.mjs` implements both browser transports on the platform APIs alone; it has no runtime
dependencies.

- `FetchCall`: binary gRPC-Web (`application/grpc-web+proto`) for a single request message. The
  response body is read only as `next` is called.
- `SocketCall`: `grpc-websockets` in the improbable-eng/grpc-web wire format: metadata in the
  first binary WebSocket message, body chunks prefixed with `0`, a single `1` for request
  half-close, and gRPC-Web message/metadata envelopes in the response. Given a single request
  message it sends that and half-closes; otherwise the caller streams requests through `send`.

Both feed their response bytes to the `FrameDecoder` in `src/wire.mjs`, and the shared `Call`
turns the frames into messages and the final status. Neither implements the Connect protocol or
compressed frames. Messages are plain `Uint8Array`s.

`npm test` (also `./gradlew :kurpc:testWebBridge`, part of `check`) runs `test/` on Node against
fake `fetch` and `WebSocket` implementations. `kurpc/src/webTest` runs the bundled bridge through
the Kotlin channel on both targets.

The build tools are pinned in `package-lock.json`. esbuild creates one self-contained bridge;
Babel lowers and normalizes its syntax for Kotlin's inline parser. Both targets compile the same
literal `@JsFun` implementation into their KLIBs. There is no `eval`, global script, separate npm
publication, or consumer-local file dependency. The license of the Babel helpers that lowering
adds is embedded in the bundle and shipped as a resource.
