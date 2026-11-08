/** The gRPC status codes the bridge produces itself. */
export const Status = {
    CANCELLED: 1,
    UNKNOWN: 2,
    DEADLINE_EXCEEDED: 4,
    PERMISSION_DENIED: 7,
    RESOURCE_EXHAUSTED: 8,
    UNIMPLEMENTED: 12,
    INTERNAL: 13,
    UNAVAILABLE: 14,
    DATA_LOSS: 15,
    UNAUTHENTICATED: 16,
};

/** A failure with a status of its own. Any other error is a transport failure. */
export class RpcError extends Error {
    constructor(code, message, trailers) {
        super(message);
        this.code = code;
        this.trailers = trailers;
    }
}

export class Metadata {
    constructor(entries = []) {
        this.entries = entries;
    }

    get size() {
        return this.entries.length;
    }

    key(index) {
        return this.entries[index][0];
    }

    value(index) {
        return this.entries[index][1];
    }

    add(key, value) {
        if (key.length === 0 || key.includes(":") || key.includes("\r") || key.includes("\n") ||
            value.includes("\r") || value.includes("\n")) {
            throw new Error("invalid RPC metadata");
        }
        this.entries.push([key.toLowerCase(), value]);
    }

    get(key) {
        return this.entries.find(entry => entry[0] === key)?.[1];
    }

    static fromHeaders(headers) {
        return new Metadata(Array.from(headers?.entries() ?? []));
    }

    static parse(bytes) {
        const result = new Metadata();
        for (const line of new TextDecoder("utf-8", {fatal: true}).decode(bytes).split("\r\n")) {
            if (line.length === 0) continue;
            const colon = line.indexOf(":");
            if (colon <= 0) {
                throw new Error("malformed RPC metadata frame");
            }
            result.add(line.slice(0, colon), line.slice(colon + 1).trim());
        }
        return result;
    }
}

export function frame(data, flags = 0) {
    const result = new Uint8Array(data.length + 5);
    result[0] = flags;
    new DataView(result.buffer).setUint32(1, data.length);
    result.set(data, 5);
    return result;
}

// Parse incrementally so fragmented frames never cause repeated full-buffer
// copies. A WebSocket message is not necessarily a complete gRPC frame.
export class FrameDecoder {
    constructor(limit, onFrame) {
        this.limit = limit;
        this.onFrame = onFrame;
        this.header = new Uint8Array(5);
        this.headerLength = 0;
        this.body = null;
        this.bodyLength = 0;
    }

    push(chunk) {
        let offset = 0;
        while (offset < chunk.length) {
            if (this.body === null) {
                const n = Math.min(5 - this.headerLength, chunk.length - offset);
                this.header.set(chunk.subarray(offset, offset + n), this.headerLength);
                this.headerLength += n;
                offset += n;
                if (this.headerLength !== 5) {
                    continue;
                }
                const size = new DataView(this.header.buffer).getUint32(1);
                if (size > this.limit) {
                    throw new RpcError(Status.RESOURCE_EXHAUSTED, "RPC frame exceeds maxMessageBytes");
                }
                if (this.header[0] !== 0 && this.header[0] !== 128) {
                    throw new RpcError(Status.UNIMPLEMENTED, "compressed or unknown RPC frame flags");
                }
                this.body = new Uint8Array(size);
            }
            const n = Math.min(this.body.length - this.bodyLength, chunk.length - offset);
            this.body.set(chunk.subarray(offset, offset + n), this.bodyLength);
            offset += n;
            this.bodyLength += n;
            if (this.bodyLength === this.body.length) {
                const data = this.body;
                this.body = null;
                this.bodyLength = 0;
                this.headerLength = 0;
                this.onFrame(this.header[0], data);
            }
        }
    }

    finish() {
        if (this.headerLength !== 0 || this.body !== null) {
            throw new RpcError(Status.DATA_LOSS, "truncated RPC frame");
        }
    }
}

export function result(kind, {data, headers, trailers, code = 0, description = ""} = {}) {
    return {
        kind, message: data ?? null,
        headers: headers ?? new Metadata(), trailers: trailers ?? new Metadata(),
        code, description,
    };
}
