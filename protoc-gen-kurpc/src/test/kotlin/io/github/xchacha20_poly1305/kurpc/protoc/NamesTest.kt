package io.github.xchacha20_poly1305.kurpc.protoc

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions
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
        val types = JavaTypes(listOf(nested, multiple, noPackage))
        assertEquals(ClassName("pkg", "A", "Outer", "Inner"), types.message(".pkg.Outer.Inner"))
        assertEquals(ClassName("com.example", "Outer", "Inner"), types.message(".pkg.b.Outer.Inner"))
        assertEquals(ClassName("", "C", "Bare"), types.message(".Bare"))
    }
}

private fun file(name: String, configure: FileDescriptorProto.Builder.() -> Unit = {}): FileDescriptorProto =
    FileDescriptorProto.newBuilder().setName(name).also(configure).build()

private fun message(name: String, configure: DescriptorProto.Builder.() -> Unit = {}): DescriptorProto =
    DescriptorProto.newBuilder().setName(name).also(configure).build()
