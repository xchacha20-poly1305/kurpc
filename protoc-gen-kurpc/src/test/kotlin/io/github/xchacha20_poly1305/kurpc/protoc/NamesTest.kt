package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test
    fun methodNamesBecomeLowerCamelCase() {
        assertEquals("getVersion", lowerCamelCase("GetVersion"))
        assertEquals("urlTest", lowerCamelCase("URLTest"))
        assertEquals("http", lowerCamelCase("HTTP"))
        assertEquals("subscribeOpenVPNStatus", lowerCamelCase("SubscribeOpenVPNStatus"))
        assertEquals("url2Go", lowerCamelCase("URL2Go"))
        assertEquals("getVersion", lowerCamelCase("get_version"))
        assertEquals("in", lowerCamelCase("In"))
    }

    @Test
    fun fileNamesBecomeUpperCamelCaseLikeProtoc() {
        assertEquals("StartedService", underscoresToCamelCase("started_service"))
        assertEquals("Foo2Bar", underscoresToCamelCase("foo2bar"))
        assertEquals("FooBar", underscoresToCamelCase("foo-bar"))
        assertEquals("HTTPApi", underscoresToCamelCase("HTTPApi"))
    }

    @Test
    fun outerClassNameAvoidsClashesAnywhereInTheFile() {
        val nestedClash = file("health.proto") {
            addMessageType(message("Response") { addNestedType(message("Health")) })
        }
        assertEquals("HealthOuterClass", outerClassName(nestedClash))
        assertEquals("Health", outerClassName(file("dir/health.proto")))
        val explicit = file("health.proto") {
            setOptions(FileOptions.newBuilder().setJavaOuterClassname("HealthProto"))
            addMessageType(message("Health"))
        }
        assertEquals("HealthProto", outerClassName(explicit))
    }

    @Test
    fun fileNameConversionHandlesSeparatorsDigitsAndExistingCapitals() {
        val cases = mapOf(
            "" to "",
            "___--.." to "",
            "__foo__bar__" to "FooBar",
            "foo.bar-baz_qux" to "FooBarBazQux",
            "foo123bar" to "Foo123Bar",
            "123foo" to "123Foo",
            "foo9" to "Foo9",
            "foo2BAR" to "Foo2BAR",
            "hTTP_api" to "HTTPApi",
            "fooébar" to "FooBar",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, underscoresToCamelCase(input), input)
        }
    }

    @Test
    fun outerClassNameUsesOnlyTheBasenameAndRecognizedSuffix() {
        val cases = mapOf(
            "dir_with_underscores/nested/my_api.proto" to "MyApi",
            "dir.proto/my_api.protodevel" to "MyApi",
            "my_api" to "MyApi",
            "my.proto.api.proto" to "MyProtoApi",
            "my_api.PROTO" to "MyApiPROTO",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, outerClassName(file(input)), input)
        }
    }

    @Test
    fun outerClassNameDetectsMessageEnumAndServiceClashes() {
        val clash = EnumDescriptorProto.newBuilder().setName("Health").build()
        val cases = listOf(
            file("health.proto") { addMessageType(message("Health")) },
            file("health.proto") { addEnumType(clash) },
            file("health.proto") {
                addService(ServiceDescriptorProto.newBuilder().setName("Health"))
            },
            file("health.proto") {
                addMessageType(message("Response") { addEnumType(clash) })
            },
            file("health.proto") {
                addMessageType(message("Response") {
                    addNestedType(message("Details") { addNestedType(message("Health")) })
                })
            },
            file("health.proto") {
                addMessageType(message("Response") {
                    addNestedType(message("Details") { addEnumType(clash) })
                })
            },
        )
        for ((index, descriptor) in cases.withIndex()) {
            assertEquals("HealthOuterClass", outerClassName(descriptor), "clash case $index")
        }
    }

    @Test
    fun outerClassNameClashesAreCaseSensitiveAndMatchTheWholeName() {
        val descriptor = file("health.proto") {
            addMessageType(message("health"))
            addMessageType(message("HealthRequest"))
            addMessageType(message("HealthOuterClass"))
        }
        assertEquals("Health", outerClassName(descriptor))
    }

    @Test
    fun explicitOuterClassNameIsPreservedWithoutCamelCaseConversion() {
        val descriptor = file("health.proto") {
            setOptions(FileOptions.newBuilder().setJavaOuterClassname("custom_API2"))
            addMessageType(message("Health"))
        }
        val types = JavaRuntime(listOf(descriptor))
        assertEquals("custom_API2", outerClassName(descriptor))
        assertEquals(ClassName("", "custom_API2", "Health"), types.type(".Health"))
    }

    @Test
    fun javaPackageCanExplicitlyOverrideTheProtoPackageWithAnEmptyPackage() {
        val descriptor = file("health.proto") {
            setPackage("proto.pkg")
            setOptions(FileOptions.newBuilder().setJavaPackage(""))
            addMessageType(message("Request"))
        }
        assertEquals("", javaPackage(descriptor))
        assertEquals(
            ClassName("", "Health", "Request"),
            JavaRuntime(listOf(descriptor)).type(".proto.pkg.Request"),
        )
    }

    @Test
    fun typeLookupKeepsProtoNamesSeparateFromJavaNamesAcrossFiles() {
        val files = listOf(false, true).map { multiple ->
            file("api.proto") {
                setPackage(if (multiple) "proto.multiple" else "proto.single")
                setOptions(
                    FileOptions.newBuilder()
                        .setJavaPackage(if (multiple) "java.multiple" else "java.single")
                        .setJavaOuterClassname("ApiContainer")
                        .setJavaMultipleFiles(multiple),
                )
                addMessageType(message("raw_message") {
                    addNestedType(message("HTTPReply") { addNestedType(message("Detail2")) })
                })
            }
        }
        val types = JavaRuntime(files)
        for (multiple in listOf(false, true)) {
            val suffix = if (multiple) "multiple" else "single"
            val enclosing = if (multiple) emptyList() else listOf("ApiContainer")
            val names = listOf("raw_message", "HTTPReply", "Detail2")
            for (depth in 1..names.size) {
                val nestedNames = names.take(depth)
                assertEquals(
                    ClassName("java.$suffix", enclosing + nestedNames),
                    types.type(".proto.$suffix.${nestedNames.joinToString(".")}"),
                )
            }
        }
    }

    @Test
    fun inferredOuterClassClashIsUsedInMessageTypeMappings() {
        val descriptor = file("health.proto") {
            setPackage("pkg")
            addMessageType(message("Health") { addNestedType(message("Request")) })
        }
        assertEquals(
            ClassName("pkg", "HealthOuterClass", "Health", "Request"),
            JavaRuntime(listOf(descriptor)).type(".pkg.Health.Request"),
        )
    }

    @Test
    fun messagesNestInTheFileClassUnlessMultipleFiles() {
        val nested = file("a.proto") {
            setPackage("pkg")
            addMessageType(message("Outer") { addNestedType(message("Inner")) })
        }
        val multiple = file("b.proto") {
            setPackage("pkg.b")
            setOptions(FileOptions.newBuilder().setJavaPackage("com.example").setJavaMultipleFiles(true))
            addMessageType(message("Outer") { addNestedType(message("Inner")) })
        }
        val noPackage = file("c.proto") { addMessageType(message("Bare")) }
        val types = JavaRuntime(listOf(nested, multiple, noPackage))
        assertEquals(ClassName("pkg", "A", "Outer", "Inner"), types.type(".pkg.Outer.Inner"))
        assertEquals(ClassName("com.example", "Outer", "Inner"), types.type(".pkg.b.Outer.Inner"))
        assertEquals(ClassName("", "C", "Bare"), types.type(".Bare"))
    }
}

private fun file(name: String, configure: FileDescriptorProto.Builder.() -> Unit = {}): FileDescriptorProto =
    FileDescriptorProto.newBuilder().setName(name).also(configure).build()

private fun message(name: String, configure: DescriptorProto.Builder.() -> Unit = {}): DescriptorProto =
    DescriptorProto.newBuilder().setName(name).also(configure).build()
