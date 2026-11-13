package io.github.xchacha20_poly1305.kurpc.wire

import com.squareup.wire.kotlin.KotlinGenerator
import com.squareup.wire.schema.Extend
import com.squareup.wire.schema.Field
import com.squareup.wire.schema.Schema
import com.squareup.wire.schema.SchemaHandler
import com.squareup.wire.schema.Service
import com.squareup.wire.schema.Type
import okio.Path

/** Wire custom target generating kurpc descriptors and suspend/Flow clients. */
public class KurpcSchemaHandlerFactory : SchemaHandler.Factory {
    override fun create(
        includes: List<String>,
        excludes: List<String>,
        exclusive: Boolean,
        outDirectory: String,
        options: Map<String, String>,
    ): SchemaHandler {
        require(options.isEmpty()) { "kurpc-wire does not accept custom options: ${options.keys}" }
        return KurpcSchemaHandler()
    }
}

private class KurpcSchemaHandler : SchemaHandler() {
    private lateinit var schema: Schema

    override fun handle(schema: Schema, context: Context) {
        this.schema = schema
        val generator = KotlinGenerator(schema)
        val names = mutableSetOf<String>()
        for (file in schema.protoFiles) {
            for (type in file.types) {
                if (!KotlinGenerator.builtInType(type.type)) {
                    names += generator.generatedTypeName(type).canonicalName
                }
            }
        }
        for (file in schema.protoFiles) {
            for (service in file.services) {
                val packageName = generator.generatedServiceName(service).packageName
                for (suffix in listOf("Kurpc", "Client")) {
                    val name = listOf(packageName, service.name + suffix)
                        .filter { it.isNotEmpty() }
                        .joinToString(".")
                    require(names.add(name)) {
                        "Generated kurpc name conflicts with another declaration: $name"
                    }
                }
                val methodNames = service.rpcs.map {
                    lowerCamelCase(it.name)
                }
                require(
                    methodNames.distinct().size == methodNames.size
                            && "SERVICE_NAME" !in methodNames,
                ) {
                    "RPC names collide after Kotlin naming in ${service.type}"
                }
            }
        }
        super.handle(schema, context)
    }

    override fun handle(type: Type, context: Context): Path? = null

    override fun handle(extend: Extend, field: Field, context: Context): Path? = null

    override fun handle(service: Service, context: Context): List<Path> {
        val file = ServiceGenerator(service, schema).generate()
        val path = context.outDirectory.resolve(file.relativePath)
        checkPathInOutDirectory(path, context.outDirectory)
        context.fileSystem.createDirectories(requireNotNull(path.parent))
        context.fileSystem.write(path) { writeUtf8(file.toString()) }
        context.logger.artifactHandled(path, service.type.toString(), "kurpc")
        return listOf(path)
    }
}
