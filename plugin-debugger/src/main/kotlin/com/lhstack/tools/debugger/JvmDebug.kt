package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.sun.jdi.*
import com.sun.jdi.event.*
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** JDWP/JDI 仅在控制端运行，目标 JVM 全暂停时不依赖目标 RPC 恢复。 */
internal class JvmDebug(port: Int) : AutoCloseable {
    private val vm: VirtualMachine
    private val running = AtomicBoolean(true)
    private val requests = ConcurrentHashMap<String, MutableList<EventRequest>>()
    private val pending = ConcurrentHashMap<String, Pair<String, Int>>()
    private val records = ArrayDeque<Map<String, Any?>>()
    private var cursor = 0L
    private val eventThread: Thread

    init {
        val connector = Bootstrap.virtualMachineManager().attachingConnectors()
            .single { it.name() == "com.sun.jdi.SocketAttach" }
        val arguments = connector.defaultArguments()
        arguments.getValue("hostname").setValue("127.0.0.1")
        arguments.getValue("port").setValue(port.toString())
        arguments.getValue("timeout").setValue("5000")
        vm = connector.attach(arguments)
        eventThread = Thread(::consumeEvents, "jtools-jdi-$port").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun call(command: String, args: JsonObject): Any? = when (command) {
        "breakpoint" -> breakpoint(args)
        "exception" -> exception(args)
        "events" -> synchronized(records) {
            val after = args.get("after")?.asLong ?: 0L
            mapOf("events" to records.filter { (it["seq"] as Long) > after }.take(200),
                "cursor" to cursor, "oldest" to records.firstOrNull()?.get("seq"))
        }
        "threads" -> vm.allThreads().map { thread -> mapOf("thread_id" to thread.uniqueID().toString(), "name" to thread.name(), "suspended" to thread.isSuspended, "status" to thread.status()) }
        "frames" -> thread(args).frames().take(100).mapIndexed { index, frame -> location(frame.location()) + mapOf("frame" to index) }
        "variables" -> {
            val frame = thread(args).frame(args.number("frame", 0))
            val variables = frame.visibleVariables().take(200)
            frame.getValues(variables).map { (variable, value) -> mapOf("name" to variable.name(), "type" to variable.typeName(), "value" to value(value)) }
        }
        "fields" -> {
            val frame = thread(args).frame(args.number("frame", 0))
            val variable = frame.visibleVariableByName(args.text("variable")) ?: error("局部变量不存在")
            val objectValue = frame.getValue(variable) as? ObjectReference ?: error("变量不是对象引用")
            val fields = objectValue.referenceType().allFields().take(200)
            objectValue.getValues(fields).map { (field, fieldValue) -> mapOf("name" to field.name(), "type" to field.typeName(), "value" to value(fieldValue)) }
        }
        "pause" -> { vm.suspend(); mapOf("state" to "SUSPENDED", "note" to "恢复需匹配所有暂停计数") }
        "resume" -> { vm.resume(); mapOf("resume_requested" to true) }
        "step" -> {
            val thread = thread(args)
            check(thread.isSuspended) { "线程未暂停" }
            val depth = when (args.text("depth")) { "into" -> StepRequest.STEP_INTO; "over" -> StepRequest.STEP_OVER; "out" -> StepRequest.STEP_OUT; else -> error("depth必须为into/over/out") }
            vm.eventRequestManager().stepRequests().filter { it.thread() == thread }.forEach { vm.eventRequestManager().deleteEventRequest(it) }
            val request = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, depth)
            val id = register(request)
            request.addCountFilter(1)
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            request.enable()
            vm.resume()
            mapOf("request_id" to id)
        }
        "remove" -> {
            val id = args.text("request_id")
            pending.remove(id)
            val removed = requests.remove(id) ?: error("调试请求不存在")
            vm.eventRequestManager().deleteEventRequests(removed.toList())
            mapOf("removed" to true)
        }
        else -> error("未知JVM调试命令: $command")
    }

    private fun breakpoint(args: JsonObject): Any {
        val className = args.text("class")
        val line = args.number("line", -1)
        require(line > 0)
        val id = UUID.randomUUID().toString()
        requests[id] = java.util.Collections.synchronizedList(mutableListOf())
        pending[id] = className to line
        // 先注册prepare，再检查已加载类型，避免类加载窗口丢断点。
        val prepare = vm.eventRequestManager().createClassPrepareRequest()
        prepare.addClassFilter(className)
        prepare.putProperty("request_id", id)
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        requests.getValue(id).add(prepare)
        prepare.enable()
        try {
            vm.classesByName(className).filter { it.isPrepared }.forEach { bind(id, it, line) }
        } catch (error: Throwable) {
            pending.remove(id)
            requests.remove(id)?.let { vm.eventRequestManager().deleteEventRequests(it.toList()) }
            throw error
        }
        return mapOf("request_id" to id, "bound" to (requests.getValue(id).size > 1))
    }

    @Synchronized private fun bind(id: String, type: ReferenceType, line: Int) {
        val entries = requests[id] ?: return
        if (entries.any { it is com.sun.jdi.request.BreakpointRequest && it.location().declaringType() == type }) return
        val locations = type.locationsOfLine(line)
        check(locations.isNotEmpty()) { "类 ${type.name()} 的第 $line 行无可执行位置" }
        locations.distinct().forEach { location ->
            val request = vm.eventRequestManager().createBreakpointRequest(location)
            request.putProperty("request_id", id)
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            entries.add(request)
            request.enable()
        }
        record("breakpoint.bound", mapOf("request_id" to id, "class" to type.name(), "line" to line))
    }

    private fun exception(args: JsonObject): Any {
        val name = args.text("class")
        val type = vm.classesByName(name).singleOrNull() ?: error("异常类必须已加载且唯一: $name")
        val request = vm.eventRequestManager().createExceptionRequest(type, args.get("caught")?.asBoolean ?: false, args.get("uncaught")?.asBoolean ?: true)
        val id = register(request)
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        request.enable()
        return mapOf("request_id" to id)
    }

    private fun register(request: EventRequest): String {
        val id = UUID.randomUUID().toString()
        request.putProperty("request_id", id)
        requests[id] = java.util.Collections.synchronizedList(mutableListOf(request))
        return id
    }
    private fun thread(args: JsonObject): ThreadReference = vm.allThreads().singleOrNull { it.uniqueID().toString() == args.text("thread_id") } ?: error("线程不存在或已退出")
    private fun value(value: Value?): Any? = when (value) {
        null -> null
        is StringReference -> value.value().take(4000)
        is PrimitiveValue -> value.toString()
        is ObjectReference -> mapOf("object_id" to value.uniqueID().toString(), "type" to value.referenceType().name())
        else -> value.toString().take(4000)
    }
    private fun location(location: Location): Map<String, Any?> = mapOf("class" to location.declaringType().name(), "method" to location.method().name(), "line" to location.lineNumber(), "code_index" to location.codeIndex())
    private fun record(type: String, payload: Map<String, Any?>) = synchronized(records) {
        records.addLast(payload + mapOf("seq" to ++cursor, "type" to type, "time" to System.currentTimeMillis()))
        while (records.size > 1000) records.removeFirst()
    }
    private fun consumeEvents() {
        try {
            while (running.get()) {
                val batch = vm.eventQueue().remove(500) ?: continue
                var hold = false
                for (event in batch) {
                    when (event) {
                        is ClassPrepareEvent -> {
                            val id = event.request().getProperty("request_id") as String
                            val target = pending[id]
                            if (target != null) try { bind(id, event.referenceType(), target.second) }
                            catch (error: Throwable) { record("breakpoint.error", mapOf("request_id" to id, "error" to error.toString())) }
                        }
                        is LocatableEvent -> {
                            hold = true
                            record(event.javaClass.simpleName, location(event.location()) + mapOf("thread_id" to event.thread().uniqueID().toString(), "request_id" to event.request()?.getProperty("request_id")))
                        }
                        is VMDeathEvent, is VMDisconnectEvent -> { record("disconnected", emptyMap()); running.set(false) }
                        else -> record(event.javaClass.simpleName, emptyMap())
                    }
                }
                if (!hold) batch.resume()
            }
        } catch (_: VMDisconnectedException) { record("disconnected", emptyMap()) }
        catch (error: InterruptedException) { if (running.get()) record("listener.error", mapOf("error" to error.toString())) }
        catch (error: Throwable) { record("listener.error", mapOf("error" to error.toString())) }
    }
    override fun close() {
        if (running.getAndSet(false)) {
            try { vm.dispose() } catch (_: VMDisconnectedException) { /* 目标已退出，连接已释放。 */ }
        }
        eventThread.interrupt()
    }
}
