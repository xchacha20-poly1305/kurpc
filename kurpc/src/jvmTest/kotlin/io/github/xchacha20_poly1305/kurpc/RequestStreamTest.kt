package io.github.xchacha20_poly1305.kurpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel as Mailbox
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

public class RequestStreamTest {
    @Test
    public fun clientStreamSendsEveryMessageThenHalfCloses() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val requests = flowOf("a", "bb", "ccc").map { it.encodeToByteArray() }
                assertEquals("3:6", channel.clientStreaming(COLLECT, requests).decodeToString())
                assertEquals("0:0", channel.clientStreaming(COLLECT, emptyFlow()).decodeToString())
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun serverAnswerStopsAnUnfinishedRequestFlow() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val requests = flow {
                    emit("a".encodeToByteArray())
                    emit("fail".encodeToByteArray())
                    // Never completes; the server's answer has to end the call anyway.
                    awaitCancellation()
                }
                val error = assertFailsWith<StatusException> { channel.clientStreaming(COLLECT, requests) }
                assertEquals(Status.Code.INVALID_ARGUMENT, error.code)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun failingRequestFlowCancelsTheCall() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val requests = flow {
                    emit("a".encodeToByteArray())
                    // A send only queues the message; failing before the server has it can
                    // cancel the call before it reaches the server, leaving no handler to end.
                    awaitStat(channel, "requests_received", 1)
                    throw IllegalStateException("requests failed")
                }
                val error = assertFailsWith<IllegalStateException> { channel.clientStreaming(COLLECT, requests) }
                assertEquals("requests failed", error.message)
                // The request stream was reset, not half-closed, yet the handler still ended.
                awaitStat(channel, "collect_ended", 1)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun bidiEchoesUntilRequestsComplete() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val requests = flowOf("one", "two", "three").map { it.encodeToByteArray() }
                val replies = channel.bidiStreaming(CHAT, requests).toList().map { it.decodeToString() }
                assertEquals(listOf("one", "two", "three"), replies)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun bidiDirectionsRunConcurrently() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                // Each request waits for the reply to the previous one, so this only finishes if
                // responses are collected while the request flow is suspended.
                val replied = Mailbox<Unit>(Mailbox.UNLIMITED)
                val requests = flow {
                    for (index in 0 until 5) {
                        emit("m$index".encodeToByteArray())
                        replied.receive()
                    }
                }
                val replies = channel.bidiStreaming(CHAT, requests)
                    .map { reply -> reply.decodeToString().also { replied.send(Unit) } }
                    .toList()
                assertEquals((0 until 5).map { "m$it" }, replies)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun serverEndingBidiFirstCompletesTheFlow() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val requests = flow {
                    emit("hi".encodeToByteArray())
                    emit("bye".encodeToByteArray())
                    awaitCancellation()
                }
                val replies = channel.bidiStreaming(CHAT, requests).toList().map { it.decodeToString() }
                assertEquals(listOf("hi"), replies)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun cancellingBidiCollectionReachesTheServer() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val requests = flow {
                    var index = 0
                    while (true) {
                        emit("m${index++}".encodeToByteArray())
                    }
                }
                assertEquals(1, channel.bidiStreaming(CHAT, requests).take(1).toList().size)
                awaitStat(channel, "chat_cancelled", 1)
            }
        }
        awaitIdleHandles()
    }
}
