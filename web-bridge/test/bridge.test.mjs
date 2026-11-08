import {afterEach, test} from "node:test";
import assert from "node:assert/strict";
import {metadata, open} from "../src/index.mjs";
import {frame} from "../src/wire.mjs";

const METADATA_FRAME = 128;
const NO_TIMEOUT = -1;
const MAX_MESSAGE = 1024;
const QUEUE_BYTES = 4096;
const QUEUE_MESSAGES = 2;
const SEND_BUFFER = 1024;
const PATH = "/test.Service/Method";

const realFetch = globalThis.fetch;
const realWebSocket = globalThis.WebSocket;

afterEach(() => {
    globalThis.fetch = realFetch;
    globalThis.WebSocket = realWebSocket;
});

function headers(entries = {}) {
    const result = metadata();
    for (const [key, value] of Object.entries(entries)) {
        result.add(key, value);
    }
    return result;
}

function trailerFrame(text) {
    return frame(new TextEncoder().encode(text), METADATA_FRAME);
}

function concat(...parts) {
    return Uint8Array.from(parts.flatMap(part => [...part]));
}

function start({useSocket = false, request = Uint8Array.of(1, 2), timeout = NO_TIMEOUT, callHeaders = headers()} = {}) {
    return open(
        "http://example.org/api", null, PATH, useSocket, request, callHeaders, timeout,
        MAX_MESSAGE, QUEUE_BYTES, QUEUE_MESSAGES, SEND_BUFFER,
    );
}

/** Answers every fetch with [chunks] as the body and records the request. */
function fakeFetch(chunks, init = {}) {
    const requests = [];
    globalThis.fetch = async (url, request) => {
        requests.push({url, ...request});
        const body = new ReadableStream({
            start(controller) {
                chunks.forEach(chunk => controller.enqueue(chunk));
                controller.close();
            },
        });
        return new Response(body, init);
    };
    return requests;
}

class FakeSocket {
    static CLOSING = 2;
    static latest;

    constructor(url, protocol) {
        this.url = url;
        this.protocol = protocol;
        this.readyState = 1;
        this.bufferedAmount = 0;
        this.sent = [];
        FakeSocket.latest = this;
        queueMicrotask(() => this.onopen());
    }

    send(data) {
        this.sent.push(data);
    }

    receive(data) {
        this.onmessage({data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)});
    }

    close() {
        this.readyState = 3;
    }
}

async function openedSocket(options) {
    globalThis.WebSocket = FakeSocket;
    const call = start({useSocket: true, ...options});
    await new Promise(resolve => setTimeout(resolve, 0));
    return {call, socket: FakeSocket.latest};
}

test("fetch sends one framed request and reads frames split across chunks", async () => {
    const response = concat(frame(Uint8Array.of(7, 8, 9)), frame(Uint8Array.of(10)), trailerFrame("grpc-status: 0\r\n"));
    const requests = fakeFetch([response.subarray(0, 3), response.subarray(3, 11), response.subarray(11)]);

    const call = start({callHeaders: headers({authorization: "Bearer secret"})});
    const first = await call.next();
    const second = await call.next();
    const end = await call.next();

    assert.equal(requests[0].url, "http://example.org/api" + PATH);
    assert.equal(requests[0].method, "POST");
    assert.deepEqual(requests[0].headers, [
        ["authorization", "Bearer secret"],
        ["content-type", "application/grpc-web+proto"],
        ["x-grpc-web", "1"],
    ]);
    assert.deepEqual(requests[0].body, frame(Uint8Array.of(1, 2)));
    assert.deepEqual([...first.message], [7, 8, 9]);
    assert.deepEqual([...second.message], [10]);
    assert.equal(end.kind, "end");
    assert.equal(end.trailers.get("grpc-status"), "0");
});

test("fetch reports the status of a response without messages from its HTTP headers", async () => {
    fakeFetch([], {headers: {"grpc-status": "5", "grpc-message": "no%20such%20thing"}});

    const result = await start().next();

    assert.equal(result.kind, "error");
    assert.equal(result.code, 5);
    assert.equal(result.description, "no such thing");
});

test("fetch maps an HTTP failure to its gRPC status", async () => {
    fakeFetch([], {status: 401});

    const result = await start().next();

    assert.equal(result.code, 16);
    assert.equal(result.description, "HTTP 401");
});

test("fetch fails a response that ends without a status", async () => {
    fakeFetch([frame(Uint8Array.of(7))]);
    const call = start();

    assert.equal((await call.next()).kind, "message");
    const result = await call.next();

    assert.equal(result.kind, "error");
    assert.equal(result.code, -1);
});

test("a single request over WebSocket is sent, half-closed, and keeps receiving", async () => {
    const {call, socket} = await openedSocket({callHeaders: headers({authorization: "Bearer secret"})});

    assert.equal(socket.url.href, "ws://example.org/api" + PATH);
    assert.equal(socket.protocol, "grpc-websockets");
    assert.equal(
        new TextDecoder().decode(socket.sent[0]),
        "authorization: Bearer secret\r\ncontent-type: application/grpc-web+proto\r\n",
    );
    assert.deepEqual(socket.sent[1], concat([0], frame(Uint8Array.of(1, 2))));
    assert.deepEqual(socket.sent[2], Uint8Array.of(1));

    socket.receive(trailerFrame("x-header: yes\r\n"));
    socket.receive(frame(Uint8Array.of(7)));
    const first = await call.next();
    socket.receive(concat(frame(Uint8Array.of(8)), trailerFrame("grpc-status: 0\r\n")));
    const second = await call.next();
    const end = await call.next();

    assert.deepEqual([...first.message], [7]);
    assert.equal(first.headers.get("x-header"), "yes");
    assert.deepEqual([...second.message], [8]);
    assert.equal(end.kind, "end");
});

test("a request stream over WebSocket half-closes only when asked", async () => {
    const {call, socket} = await openedSocket({request: null});

    assert.equal((await call.send(Uint8Array.of(3))).kind, "sent");
    assert.equal(socket.sent.length, 2);
    call.halfClose();
    await new Promise(resolve => setTimeout(resolve, 0));

    assert.deepEqual(socket.sent[1], concat([0], frame(Uint8Array.of(3))));
    assert.deepEqual(socket.sent[2], Uint8Array.of(1));
});

test("WebSocket messages beyond the queue limit end the call", async () => {
    const {call, socket} = await openedSocket();

    for (let index = 0; index <= QUEUE_MESSAGES; index++) {
        socket.receive(frame(Uint8Array.of(index)));
    }
    const result = await call.next();

    assert.equal(result.kind, "error");
    assert.equal(result.code, 8);
});

test("a WebSocket that closes without a status is a transport failure", async () => {
    const {call, socket} = await openedSocket();

    socket.onclose();
    const result = await call.next();

    assert.equal(result.code, -1);
});

test("an invalid method path fails the call without a request", async () => {
    const requests = fakeFetch([]);
    const call = open(
        "http://example.org", null, "/bad path", false, Uint8Array.of(1), headers(), NO_TIMEOUT,
        MAX_MESSAGE, QUEUE_BYTES, QUEUE_MESSAGES, SEND_BUFFER,
    );

    const result = await call.next();

    assert.equal(result.code, -3);
    assert.equal(requests.length, 0);
});
