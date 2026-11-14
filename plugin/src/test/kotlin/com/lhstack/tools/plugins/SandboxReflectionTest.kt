package com.lhstack.tools.plugins

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.sandbox.SandboxReflection
import kotlin.test.*

class SandboxReflectionTest {
    private val reflection = SandboxReflection(javaClass.classLoader)
    private val fixture = Fixture()
    private val id = reflection.reference(fixture)["object_id"].asString

    @Test
    fun `methods expose exact overloads inherited and non public members`() {
        val methods = reflection.methods(json("""{"object_id":"$id"}"""))["methods"].asJsonArray
        assertEquals(2, methods.count { it.asJsonObject["name"].asString == "echo" })
        assertTrue(methods.any { it.asJsonObject["name"].asString == "hidden" && it.asJsonObject["visibility"].asString == "private" })
        assertTrue(methods.any { it.asJsonObject["name"].asString == "toString" })
        assertTrue(methods.none { it.asJsonObject["static"].asBoolean })
        val static = reflection.methods(json("""{"class_name":"${Fixture::class.java.name}","method":"sum"}"""))["methods"].asJsonArray
        assertEquals(1, static.size())
        assertTrue(static[0].asJsonObject["static"].asBoolean)
    }

    @Test
    fun `precise overloads private methods and arrays execute on same instance`() {
        assertEquals("int:7", invoke("echo", """["int"]""", """[{"value":7}]""")["value"].asString)
        assertEquals("string:7", invoke("echo", """["java.lang.String"]""", """[{"value":"7"}]""")["value"].asString)
        assertEquals("hidden", invoke("hidden")["value"].asString)
        assertEquals(2, invoke("arraySize", """["int[]"]""", """[{"value":[{"value":1},{"value":2}]}]""")["value"].asInt)
        invoke("increment")
        assertEquals(1, fixture.count)
        assertEquals(id, invoke("self")["object_id"].asString)
    }

    @Test
    fun `plugin function calling objects remain invocable through list contract`() {
        val pluginId = reflection.reference(TestPlugin())["object_id"].asString
        val list = reflection.invoke(json("""{"object_id":"$pluginId","method":"functionCallings","parameter_types":["java.lang.String"],"arguments":[{"value":"project"}]}"""))
        val function = reflection.invoke(json("""{"object_id":"${list["object_id"].asString}","method":"get","parameter_types":["int"],"arguments":[{"value":0}]}"""))
        val result = reflection.invoke(json("""{"object_id":"${function["object_id"].asString}","method":"call","parameter_types":["java.lang.String","java.lang.String","java.lang.String","java.lang.String","java.lang.String"],"arguments":[{"value":"payload"},{"value":"session"},{"value":"42"},{"value":"provider"},{"value":"model"}]}"""))
        assertEquals("payload:session:42:provider:model", result["value"].asString)
    }

    @Test
    fun `static calls object arguments and null values honor declared types`() {
        val result = reflection.invoke(json("""{"class_name":"${Fixture::class.java.name}","method":"sum","parameter_types":["int","int"],"arguments":[{"value":2},{"value":3}]}"""))
        assertEquals(5, result["value"].asInt)
        assertTrue(invoke("same", """["java.lang.Object"]""", """[{"object_id":"$id"}]""")["value"].asBoolean)
        assertTrue(invoke("echo", """["java.lang.String"]""", """[{"value":null}]""")["value"].asString.contains("null"))
    }

    @Test
    fun `exceptions are unwrapped and invalid contracts rejected without invocation`() {
        assertEquals("fixture failure", assertFailsWith<IllegalStateException> { invoke("fail") }.message)
        for (value in listOf("7.1", "2147483648", "\"7\"", "null")) {
            assertFails { invoke("echo", """["int"]""", """[{"value":$value}]""") }
        }
        assertFails { invoke("echo", """["int"]""", "[]") }
        assertFails { reflection.invoke(json("""{"object_id":"$id","method":"increment"}""")) }
        assertEquals(0, fixture.count)
        reflection.clear()
        assertFailsWith<IllegalStateException> { invoke("self") }
        assertNotEquals(id, reflection.reference(fixture)["object_id"].asString)
    }

    private fun invoke(method: String, types: String = "[]", arguments: String = "[]") =
        reflection.invoke(json("""{"object_id":"$id","method":"$method","parameter_types":$types,"arguments":$arguments}"""))
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    class Fixture {
        var count = 0
        fun echo(value: Int) = "int:$value"
        fun echo(value: String?) = "string:$value"
        private fun hidden() = "hidden"
        fun arraySize(values: IntArray) = values.size
        fun increment() { count++ }
        fun self() = this
        fun same(value: Any?) = value === this
        fun fail(): Nothing = error("fixture failure")
        companion object { @JvmStatic fun sum(a: Int, b: Int) = a + b }
    }
    class TestPlugin : IPlugin {
        override fun pluginIcon() = javax.swing.ImageIcon()
        override fun pluginTabIcon() = javax.swing.ImageIcon()
        override fun pluginName() = "test"
        override fun pluginDesc() = "test"
        override fun pluginVersion() = "1"

        override fun functionCallings(locationHash: String): List<FunctionCalling> = listOf(object : FunctionCalling {
            override fun name() = "test"
            override fun parameters() = "{}"
            override fun call(argumentsJson: String) = argumentsJson
            override fun call(argumentsJson: String, sessionName: String?, sessionId: String?, provider: String?, model: String?) =
                "$argumentsJson:$sessionName:$sessionId:$provider:$model"
        })
    }
}
