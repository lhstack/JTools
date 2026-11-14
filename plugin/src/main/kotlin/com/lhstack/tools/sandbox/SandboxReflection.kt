package com.lhstack.tools.sandbox

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.UUID

/** References belong to one live plugin run; complex objects never cross the process boundary. */
internal class SandboxReflection(private val classLoader: ClassLoader) {
    private val objects = mutableMapOf<String, Any>()
    private val identifiers = IdentityHashMap<Any, String>()

    @Synchronized
    fun clear() {
        objects.clear()
        identifiers.clear()
    }

    @Synchronized
    fun reference(value: Any): JsonObject {
        val id = identifiers[value] ?: UUID.randomUUID().toString().also {
            identifiers[value] = it
            objects[it] = value
        }
        return JsonObject().apply {
            addProperty("kind", "object")
            addProperty("object_id", id)
            addProperty("class_name", value.javaClass.name)
        }
    }

    fun methods(args: JsonObject): JsonObject {
        val target = target(args)
        val name = args.optionalText("method")
        return JsonObject().apply {
            addProperty("class_name", target.type.name)
            add("methods", JsonArray().apply {
                methodsOf(target.type).filter { Modifier.isStatic(it.modifiers) == target.static }
                    .filter { name == null || it.name == name }
                    .sortedBy { it.toGenericString() }.forEach { method ->
                        add(JsonObject().apply {
                            addProperty("name", method.name)
                            addProperty("declaring_class", method.declaringClass.name)
                            add("parameter_types", strings(method.parameterTypes.map(Class<*>::getTypeName)))
                            addProperty("return_type", method.returnType.typeName)
                            addProperty("static", Modifier.isStatic(method.modifiers))
                            addProperty("visibility", when {
                                Modifier.isPublic(method.modifiers) -> "public"
                                Modifier.isProtected(method.modifiers) -> "protected"
                                Modifier.isPrivate(method.modifiers) -> "private"
                                else -> "package"
                            })
                            addProperty("varargs", method.isVarArgs)
                        })
                    }
            })
        }
    }

    fun invoke(args: JsonObject): JsonObject {
        val target = target(args)
        val name = args.requiredText("method")
        val parameters = requireNotNull(args.get("parameter_types")) { "缺少 parameter_types；无参方法传 []" }
        require(parameters.isJsonArray) { "parameter_types 必须为数组" }
        val types = parameters.asJsonArray.map { resolveType(string(it, "parameter_types")) }
        val values = requireNotNull(args.get("arguments")) { "缺少 arguments；无参方法传 []" }
        require(values.isJsonArray && values.asJsonArray.size() == types.size) { "arguments 必须与 parameter_types 数量一致" }
        val method = methodsOf(target.type).firstOrNull {
            it.name == name && it.parameterTypes.toList() == types && Modifier.isStatic(it.modifiers) == target.static
        } ?: error("方法不存在：${target.type.name}.$name(${types.joinToString { it.typeName }})")
        val accessible = accessibleMethod(method, target)
        val arguments = types.mapIndexed { index, type -> argument(values.asJsonArray[index], type) }.toTypedArray()
        val result = try {
            accessible.invoke(target.instance, *arguments)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        return result(result)
    }

    private data class Target(val type: Class<*>, val instance: Any?, val static: Boolean)

    private fun target(args: JsonObject): Target {
        require(args.has("object_id") xor args.has("class_name")) { "object_id 与 class_name 必须且只能提供一个" }
        if (args.has("object_id")) {
            val value = objectById(args.requiredText("object_id"))
            return Target(value.javaClass, value, false)
        }
        return Target(resolveType(args.requiredText("class_name")), null, true)
    }

    @Synchronized
    private fun objectById(id: String): Any = objects[id] ?: error("对象引用已失效或不存在：$id")

    private fun methodsOf(type: Class<*>): List<Method> {
        val methods = linkedMapOf<String, Method>()
        val visited = mutableSetOf<Class<*>>()
        fun collect(current: Class<*>) {
            if (!visited.add(current)) return
            current.declaredMethods.filterNot { it.isBridge || it.isSynthetic }.forEach {
                val key = it.name + it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> type.name }
                methods.putIfAbsent(key, it)
            }
            current.superclass?.let(::collect)
            current.interfaces.forEach(::collect)
        }
        collect(type)
        return methods.values.toList()
    }

    private fun accessibleMethod(method: Method, target: Target): Method {
        if (method.canAccess(target.instance)) return method
        // JDK collections may have a non-public implementation. Invoke their public contract.
        if (Modifier.isPublic(method.modifiers) && !target.static) {
            fun publicContract(type: Class<*>): Method? {
                if (Modifier.isPublic(type.modifiers)) {
                    val candidate = type.methods.firstOrNull {
                        it.name == method.name && it.parameterTypes.contentEquals(method.parameterTypes) && it.canAccess(target.instance)
                    }
                    if (candidate != null) return candidate
                }
                return type.interfaces.firstNotNullOfOrNull(::publicContract) ?: type.superclass?.let(::publicContract)
            }
            publicContract(target.type)?.let { return it }
        }
        check(method.trySetAccessible()) { "JVM 禁止访问方法：$method" }
        return method
    }

    private fun argument(input: JsonElement, type: Class<*>): Any? {
        require(input.isJsonObject) { "参数须为 {\"value\":...} 或 {\"object_id\":...}" }
        val record = input.asJsonObject
        require(record.size() == 1 && (record.has("value") xor record.has("object_id"))) { "参数须且只能包含 value 或 object_id" }
        if (record.has("object_id")) {
            val value = objectById(record.requiredText("object_id"))
            require(boxed(type).isInstance(value)) { "对象 ${value.javaClass.name} 不能传入 ${type.typeName}" }
            return value
        }
        val value = record.get("value")
        if (value.isJsonNull) {
            require(!type.isPrimitive) { "基本类型 ${type.name} 不接受 null" }
            return null
        }
        if (type.isArray) {
            require(value.isJsonArray) { "${type.typeName} 参数必须为数组" }
            return ReflectArray.newInstance(type.componentType, value.asJsonArray.size()).also { array ->
                value.asJsonArray.forEachIndexed { index, item -> ReflectArray.set(array, index, argument(item, type.componentType)) }
            }
        }
        require(value.isJsonPrimitive) { "复杂对象须通过 object_id 传递" }
        val primitive = value.asJsonPrimitive
        return when (boxed(type)) {
            String::class.java -> string(value, "value")
            java.lang.Character::class.java -> string(value, "value").also { require(it.length == 1) { "char 必须为一个 UTF-16 字符" } }[0]
            java.lang.Boolean::class.java -> { require(primitive.isBoolean) { "boolean 参数必须为布尔值" }; primitive.asBoolean }
            java.lang.Byte::class.java -> number(value).toBigIntegerExact().byteValueExact()
            java.lang.Short::class.java -> number(value).toBigIntegerExact().shortValueExact()
            java.lang.Integer::class.java -> number(value).intValueExact()
            java.lang.Long::class.java -> number(value).longValueExact()
            java.lang.Float::class.java -> number(value).toFloat().also { require(it.isFinite()) { "float 参数溢出" } }
            java.lang.Double::class.java -> number(value).toDouble().also { require(it.isFinite()) { "double 参数溢出" } }
            else -> error("${type.typeName} 参数须通过 object_id 传递")
        }
    }

    private fun number(value: JsonElement): java.math.BigDecimal {
        require(value.asJsonPrimitive.isNumber) { "数值参数必须为 JSON number" }
        return value.asBigDecimal
    }

    private fun resolveType(name: String): Class<*> {
        if (name.endsWith("[]")) return ReflectArray.newInstance(resolveType(name.removeSuffix("[]")), 0).javaClass
        return primitiveTypes[name] ?: Class.forName(name, false, classLoader)
    }

    private fun boxed(type: Class<*>): Class<*> = boxes[type] ?: type

    private fun result(value: Any?): JsonObject = when (value) {
        null -> JsonObject().apply { addProperty("kind", "value"); add("value", JsonNull.INSTANCE) }
        is String, is Char, is Boolean, is Number -> JsonObject().apply {
            addProperty("kind", "value")
            when (value) {
                is Number -> { require(value !is Double || value.isFinite()); require(value !is Float || value.isFinite()); addProperty("value", value) }
                is Boolean -> addProperty("value", value)
                else -> addProperty("value", value.toString())
            }
        }
        else -> reference(value)
    }

    private fun strings(values: List<String>) = JsonArray().apply { values.forEach(::add) }

    companion object {
        private val primitiveTypes = mapOf("boolean" to Boolean::class.javaPrimitiveType!!, "byte" to Byte::class.javaPrimitiveType!!,
            "short" to Short::class.javaPrimitiveType!!, "int" to Int::class.javaPrimitiveType!!, "long" to Long::class.javaPrimitiveType!!,
            "float" to Float::class.javaPrimitiveType!!, "double" to Double::class.javaPrimitiveType!!, "char" to Char::class.javaPrimitiveType!!)
        private val boxes = mapOf<Class<*>, Class<*>>(Boolean::class.javaPrimitiveType!! to java.lang.Boolean::class.java,
            Byte::class.javaPrimitiveType!! to java.lang.Byte::class.java, Short::class.javaPrimitiveType!! to java.lang.Short::class.java,
            Int::class.javaPrimitiveType!! to java.lang.Integer::class.java, Long::class.javaPrimitiveType!! to java.lang.Long::class.java,
            Float::class.javaPrimitiveType!! to java.lang.Float::class.java, Double::class.javaPrimitiveType!! to java.lang.Double::class.java,
            Char::class.javaPrimitiveType!! to java.lang.Character::class.java)
    }
}

private fun JsonObject.requiredText(key: String): String = requireNotNull(optionalText(key)) { "缺少 $key" }
private fun JsonObject.optionalText(key: String): String? = get(key)?.let { string(it, key).also { text -> require(text.isNotBlank()) { "$key 不能为空" } } }
private fun string(value: JsonElement, label: String): String {
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$label 必须为字符串" }
    return value.asString
}
