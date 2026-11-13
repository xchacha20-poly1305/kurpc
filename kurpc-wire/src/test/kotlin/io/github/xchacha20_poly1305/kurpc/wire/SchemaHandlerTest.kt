package io.github.xchacha20_poly1305.kurpc.wire

import com.squareup.wire.WireLogger
import com.squareup.wire.schema.Location
import com.squareup.wire.schema.SchemaHandler
import com.squareup.wire.schema.SchemaLoader
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaHandlerTest {
    @Test
    fun methodNamesBecomeLowerCamelCase() {
        for ((input, expected) in
        mapOf(
            "GetVersion" to "getVersion",
            "URLTest" to "urlTest",
            "HTTP" to "http",
            "SubscribeOpenVPNStatus" to "subscribeOpenVPNStatus",
            "URL2Go" to "url2Go",
            "get_version" to "getVersion",
            "In" to "in",
        )) {
            assertEquals(expected, lowerCamelCase(input))
        }
    }

    @Test
    fun commentsAndDeprecationsArePreserved() {
        val output = generate(
            """
            message Payload {}
            // Old echo service.
            service Echo {
              option deprecated = true;
              // Old request.
              rpc Send(Payload) returns (Payload) { option deprecated = true; }
            }
        """,
        )
        assertContains(output, "Old echo service.")
        assertContains(output, "Old request.")
        assertContains(output, "@Deprecated")
        assertContains(output, "wireCodec(Payload.ADAPTER)")
        assertContains(output, "/test.Echo/Send")
    }

    @Test
    fun generatedClientNameCannotShadowAMessage() {
        val error = assertFailsWith<IllegalArgumentException> {
            generate(
                "message EchoClient {} service Echo { rpc Send(EchoClient) returns (EchoClient); }",
            )
        }
        assertContains(error.message.orEmpty(), "test.EchoClient")
    }

    @Test
    fun collidingMethodNamesAreRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            generate(
                "message Payload {} service Echo { rpc GetURL(Payload) returns (Payload); rpc get_URL(Payload) returns (Payload); }",
            )
        }
        assertContains(error.message.orEmpty(), "RPC names collide")
    }

    @Test
    fun obsoleteRuntimeOptionsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            KurpcSchemaHandlerFactory()
                .create(emptyList(), emptyList(), true, "out", mapOf("runtime" to "java"))
        }
    }

    private fun generate(body: String): String {
        val directory = createTempDirectory("kurpc-wire-test")
        try {
            directory
                .resolve("test.proto")
                .writeText("syntax = \"proto3\"; package test;\n" + body.trimIndent())
            val loader = SchemaLoader(FileSystem.SYSTEM)
            loader.initRoots(listOf(Location.get(directory.toString())))
            val schema = loader.loadSchema()
            val output = directory.resolve("out").toString().toPath()
            val handler = KurpcSchemaHandlerFactory()
                .create(emptyList(), emptyList(), true, output.toString(), emptyMap())
            handler.handle(
                schema,
                SchemaHandler.Context(
                    FileSystem.SYSTEM,
                    output,
                    WireLogger.NONE,
                    fullSchema = schema,
                ),
            )
            return FileSystem.SYSTEM.read(output.resolve("test/EchoKurpc.kt")) { readUtf8() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
