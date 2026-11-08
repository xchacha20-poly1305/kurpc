import {frame, FrameDecoder, Metadata, result, RpcError, Status} from "./wire.mjs";

// Outcomes that are not a gRPC status. WebChannel.kt turns them into its own exceptions.
const TRANSPORT_FAILURE = -1;
const CHANNEL_CLOSED = -2;
const INVALID_ARGUMENT = -3;

const HIGHEST_STATUS = Status.UNAUTHENTICATED;
const METADATA_FRAME = 128;
const CONTENT_TYPE = "application/grpc-web+proto";

export const metadata = () => new Metadata();

function baseUrl(value) {
    const url = new URL(value, globalThis.location?.href);
    if (url.protocol !== "http:" && url.protocol !== "https:") {
        throw new Error("base URL must use http or https");
    }
    if (url.search || url.hash || url.username || url.password) {
        throw new Error("base URL cannot contain query, fragment or credentials");
    }
    return url.href.endsWith("/") ? url.href.slice(0, -1) : url.href;
}

export function validateUrl(value) {
    try {
        baseUrl(value);
        return "";
    } catch (error) {
        return error.message;
    }
}

function validatePath(path) {
    const parts = path.split("/");
    if (parts.length !== 3 || parts[0] !== "" || !parts[1] || !parts[2] ||
        [...path].some(c => c.charCodeAt(0) <= 32 || "?#%\\".includes(c))) {
        throw new Error("method must be /package.Service/Method");
    }
}

// https://github.com/grpc/grpc/blob/master/doc/http-grpc-status-mapping.md
function statusOfHttp(status) {
    switch (status) {
        case 400:
            return Status.INTERNAL;
        case 401:
            return Status.UNAUTHENTICATED;
        case 403:
            return Status.PERMISSION_DENIED;
        case 404:
            return Status.UNIMPLEMENTED;
        case 429:
        case 502:
        case 503:
        case 504:
            return Status.UNAVAILABLE;
        default:
            return Status.UNKNOWN;
    }
}

function grpcMessage(md, fallback) {
    const message = md.get("grpc-message") ?? fallback;
    try {
        return decodeURIComponent(message);
    } catch { /* Keep malformed server text. */
        return message;
    }
}

/**
 * One RPC. Both transports deliver the response as gRPC-Web frames: `decoder`
 * splits them, `receive` queues messages and ends the call on the status frame.
 */
class Call {
    constructor(maxMessage) {
        this.terminal = null;
        this.headers = new Metadata();
        this.controller = new AbortController();
        this.queue = [];
        this.queuedBytes = 0;
        this.decoder = new FrameDecoder(maxMessage, (flags, data) => this.receive(flags, data));
    }

    start(timeoutMs, setup) {
        // Setup can fail after allocating a socket or timer. Keep it inside the
        // call's lifecycle so all failure paths release the same resources.
        try {
            if (timeoutMs >= 0) {
                this.deadline = performance.now() + timeoutMs;
                const tick = () => {
                    const remaining = this.deadline - performance.now();
                    if (remaining <= 0) {
                        this.finish(result("error", {
                            code: Status.DEADLINE_EXCEEDED,
                            description: "RPC deadline exceeded"
                        }), true);
                    } else {
                        this.timer = setTimeout(tick, Math.min(remaining, 2147483647));
                    }
                };
                tick();
            }
            if (!this.terminal) {
                setup();
            }
        } catch (error) {
            this.fail(error);
        }
    }

    requestHeaders(headers) {
        const entries = headers.entries.filter(([key]) => !["content-type", "grpc-timeout", "content-length"].includes(key));
        entries.push(["content-type", CONTENT_TYPE]);
        if (this.deadline !== undefined) {
            entries.push(["grpc-timeout", grpcTimeout(Math.max(0, this.deadline - performance.now()))]);
        }
        return entries;
    }

    wait(promise) {
        // Remove each listener once its operation finishes. Racing every read
        // against one unresolved Promise retains a callback per stream message.
        return new Promise((resolve, reject) => {
            const complete = (settle, value) => {
                this.controller.signal.removeEventListener("abort", stopped);
                settle(value);
            };
            const stopped = () => complete(resolve, this.terminal);
            this.controller.signal.addEventListener("abort", stopped, {once: true});
            promise.then(value => complete(resolve, value), error => complete(reject, error));
            if (this.terminal) {
                stopped();
            }
        });
    }

    finish(terminal, discard = false, replace = false) {
        if (this.terminal && !replace) return;
        this.terminal = terminal;
        clearTimeout(this.timer);
        if (discard) {
            this.queue.length = 0;
            this.queuedBytes = 0;
        }
        this.controller.abort();
        if (this.socket && this.socket.readyState < WebSocket.CLOSING) {
            try {
                this.socket.close();
            } catch { /* An unopened socket may reject close. */
            }
        }
        this.wake?.();
    }

    fail(error) {
        if (this.terminal) {
            return this.terminal;
        }
        const isStatus = error instanceof RpcError;
        this.finish(result("error", {
            code: isStatus ? error.code : TRANSPORT_FAILURE,
            description: error.message ?? String(error),
            trailers: isStatus ? error.trailers : undefined,
        }), true);
        return this.terminal;
    }

    receive(flags, data) {
        if (this.terminal) {
            throw new Error("data after gRPC status");
        }
        if (flags !== METADATA_FRAME) {
            this.queue.push(data);
            this.queuedBytes += data.length;
            this.wake?.();
            return;
        }
        const md = Metadata.parse(data);
        if (md.get("grpc-status") === undefined) {
            this.headers.entries.push(...md.entries);
        } else {
            this.complete(md);
        }
    }

    /** Ends the call with the status in [trailers]. */
    complete(trailers) {
        const rawStatus = trailers.get("grpc-status");
        const code = Number(rawStatus);
        if (rawStatus.length === 0 || [...rawStatus].some(c => c < "0" || c > "9") || code > HIGHEST_STATUS) {
            throw new RpcError(Status.INTERNAL, "invalid gRPC status");
        }
        this.finish(result(code === 0 ? "end" : "error", {
            code,
            description: grpcMessage(trailers, ""),
            headers: this.headers,
            trailers,
        }));
    }

    /** The response byte stream ended. Without a status the peer left mid-call. */
    end() {
        if (this.terminal) return;
        try {
            this.decoder.finish();
            this.fail(new Error("response ended without gRPC status"));
        } catch (error) {
            this.fail(error);
        }
    }

    take() {
        const data = this.queue.shift();
        this.queuedBytes -= data.length;
        return result("message", {data, headers: this.headers});
    }

    async next() {
        return this.terminal;
    }

    cancel() {
        this.finish(result("error", {code: Status.CANCELLED, description: "RPC cancelled"}), true, true);
    }

    close() {
        this.finish(result("error", {code: CHANNEL_CLOSED, description: "channel closed"}), true, true);
    }

    async send() {
        return this.fail(new Error("this RPC does not accept request streams"));
    }

    halfClose() {
    }
}

/**
 * gRPC-Web over fetch, for a single request message. The body is read only as
 * fast as `next` is called, so a slow consumer slows the server down.
 */
class FetchCall extends Call {
    constructor(url, request, headers, timeout, maxMessage) {
        super(maxMessage);
        this.start(timeout, () => {
            // Our timer handles durations beyond setTimeout's signed 32-bit limit,
            // so the deadline aborts the request through the call's own signal.
            this.reader = fetch(url, {
                method: "POST",
                redirect: "error",
                signal: this.controller.signal,
                headers: [...this.requestHeaders(headers), ["x-grpc-web", "1"]],
                body: frame(request),
            }).then(response => this.respond(response)).catch(error => {
                this.fail(error);
            });
        });
    }

    respond(response) {
        this.headers = Metadata.fromHeaders(response.headers);
        if (response.status < 200 || response.status >= 300) {
            throw new RpcError(
                statusOfHttp(response.status),
                grpcMessage(this.headers, `HTTP ${response.status}`),
                this.headers,
            );
        }
        // A call that fails before its first message has its status in the HTTP headers.
        if (this.headers.get("grpc-status") !== undefined) {
            this.complete(this.headers);
            return;
        }
        if (!response.body) {
            throw new RpcError(Status.UNKNOWN, "missing response body");
        }
        return response.body.getReader();
    }

    async next() {
        try {
            for (; ;) {
                if (this.queue.length) {
                    return this.take();
                }
                if (this.terminal) {
                    return this.terminal;
                }
                const reader = await this.wait(this.reader);
                if (this.terminal) continue;
                const chunk = await this.wait(reader.read());
                if (this.terminal) continue;
                if (chunk.done) {
                    this.end();
                } else {
                    this.decoder.push(chunk.value);
                }
            }
        } catch (error) {
            return this.fail(error);
        }
    }
}

/**
 * The grpc-websockets protocol of improbable-eng/grpc-web. A WebSocket cannot be
 * told to stop delivering, so messages `next` has not taken yet are queued up to
 * the configured limits.
 */
class SocketCall extends Call {
    constructor(url, request, headers, timeout, maxMessage, queueBytes, queueMessages, sendBuffer) {
        super(maxMessage);
        this.maxQueueBytes = queueBytes;
        this.maxQueueMessages = queueMessages;
        this.maxMessage = maxMessage;
        this.sendBuffer = sendBuffer;
        this.opened = new Promise(resolve => {
            this.resolveOpened = resolve;
        });
        this.start(timeout, () => this.connect(url, headers));
        if (request) {
            // A single request message is the whole request stream.
            void this.send(request).then(() => this.halfClose());
        }
    }

    connect(url, headers) {
        const target = new URL(url);
        target.protocol = target.protocol === "https:" ? "wss:" : "ws:";
        const socket = this.socket = new WebSocket(target, "grpc-websockets");
        socket.binaryType = "arraybuffer";
        socket.onopen = () => {
            if (this.terminal) return;
            try {
                if (socket.protocol !== "grpc-websockets") {
                    throw new Error("server did not select grpc-websockets");
                }
                const entries = this.requestHeaders(headers);
                socket.send(new TextEncoder().encode(entries.map(([key, value]) => `${key}: ${value}\r\n`).join("")));
                this.resolveOpened();
            } catch (error) {
                this.fail(error);
            }
        };
        socket.onmessage = event => {
            if (this.terminal) return;
            try {
                if (!(event.data instanceof ArrayBuffer)) {
                    throw new Error("non-binary WebSocket response");
                }
                this.decoder.push(new Uint8Array(event.data));
            } catch (error) {
                this.fail(error);
            }
        };
        socket.onerror = () => this.fail(new Error("WebSocket connection failed"));
        socket.onclose = () => this.end();
    }

    receive(flags, data) {
        if (flags !== METADATA_FRAME && (this.queue.length >= this.maxQueueMessages ||
            this.queuedBytes + data.length > this.maxQueueBytes)) {
            throw new RpcError(Status.RESOURCE_EXHAUSTED, "WebSocket receive queue exceeds its configured limit");
        }
        super.receive(flags, data);
    }

    async next() {
        for (; ;) {
            if (this.queue.length) return this.take();
            if (this.terminal) return this.terminal;
            await new Promise(resolve => {
                this.wake = resolve;
            });
            this.wake = null;
        }
    }

    async send(message) {
        try {
            await this.wait(this.opened);
            if (this.terminal) return this.terminal;
            if (this.halfClosed) {
                throw new Error("request stream already closed");
            }
            if (message.length > this.maxMessage) {
                throw new RpcError(Status.RESOURCE_EXHAUSTED, "request exceeds maxMessageBytes");
            }
            const encoded = frame(message);
            // Bound each WebSocket message independently of the RPC message size.
            // The peer reconstructs the framed byte stream across these chunks.
            for (let offset = 0; offset < encoded.length;) {
                const count = Math.min(encoded.length - offset, 64 * 1024, this.sendBuffer - 1);
                while (!this.terminal && this.socket.bufferedAmount + count + 1 > this.sendBuffer) {
                    await this.wait(new Promise(resolve => setTimeout(resolve, 4)));
                }
                if (this.terminal) return this.terminal;
                const packet = new Uint8Array(count + 1);
                packet.set(encoded.subarray(offset, offset + count), 1);
                this.socket.send(packet);
                offset += count;
            }
            return result("sent");
        } catch (error) {
            return this.fail(error);
        }
    }

    halfClose() {
        if (!this.terminal && !this.halfClosed) {
            this.halfClosed = true;
            // send() is awaited by its caller; opened is also awaited
            // for an empty request stream by this continuation.
            void this.wait(this.opened).then(() => {
                if (!this.terminal) {
                    this.socket.send(new Uint8Array([1]));
                }
            }).catch(error => this.fail(error));
        }
    }
}

function grpcTimeout(ms) {
    const units = [[1, "m"], [1000, "S"], [60000, "M"], [3600000, "H"]];
    for (const [divisor, suffix] of units) {
        const value = Math.ceil(ms / divisor);
        if (value < 100000000) {
            return `${value}${suffix}`;
        }
    }
    return "99999999H";
}

/**
 * Starts a call. Messages are Uint8Arrays the receiver owns. [request] is the
 * single request message, or null when the caller streams requests through
 * `send`, which only a WebSocket can carry.
 */
export function open(url, socketUrl, path, useSocket, request, headers, timeout, maxMessage, queueBytes, queueMessages, sendBuffer) {
    try {
        validatePath(path);
        const httpUrl = baseUrl(url);
        const requestUrl = baseUrl(socketUrl ?? url);
        if (request && request.length > maxMessage) {
            throw new RpcError(Status.RESOURCE_EXHAUSTED, "request exceeds maxMessageBytes");
        }
        return useSocket
            ? new SocketCall(requestUrl + path, request, headers, timeout, maxMessage, queueBytes, queueMessages, sendBuffer)
            : new FetchCall(httpUrl + path, request, headers, timeout, maxMessage);
    } catch (error) {
        const call = new Call(maxMessage);
        call.fail(error instanceof RpcError ? error : new RpcError(INVALID_ARGUMENT, error.message));
        return call;
    }
}
