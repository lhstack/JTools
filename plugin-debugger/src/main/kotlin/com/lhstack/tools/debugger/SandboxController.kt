package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal class SandboxController : AutoCloseable {
    private class Session(val owner: String, val layout: SandboxLayout, val process: Process, val port: Int, val launchKey: String, val profile: SandboxProfile?) {
        var debugger: JvmDebug? = null
        val operations = java.util.concurrent.locks.ReentrantLock()
        @Volatile var closing = false
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    fun call(owner: String, command: String, args: JsonObject): Any? {
        if (command == "help") return help()
        if (command == "start") return start(owner, args)
        val session = sessions[args.text("sandbox_id")] ?: error("沙箱不存在")
        require(session.owner == owner) { "沙箱不属于当前项目会话" }
        if (command == "status.process") return mapOf("alive" to session.process.isAlive, "pid" to session.process.pid(), "endpoint_ready" to Files.exists(session.layout.root.resolve("endpoint")), "exit_code" to if (session.process.isAlive) null else session.process.exitValue(), "startup_hint" to if (Files.exists(session.layout.root.resolve("endpoint"))) null else SandboxStartupDiagnostics.pendingMessage(session.layout.root))
        if (command == "terminate") { terminate(session); sessions.remove(session.layout.id); return mapOf("state" to "TERMINATED", "cleanup_callbacks_verified" to false) }
        if (command == "startup.logs") return readLog(session.layout.root.resolve("process.log"), args)
        if (command.startsWith("jvm.")) {
            synchronized(session) {
                check(!session.closing) { "沙箱正在终止" }
                val debugger = session.debugger ?: JvmDebug(session.port).also { session.debugger = it }
                return debugger.call(command.removePrefix("jvm."), args)
            }
        }
        check(!session.closing) { "沙箱正在终止" }
        val locked = !command.startsWith("js.") && command != "ping" && command != "swing.operation"
        if (locked && !session.operations.tryLock()) error("沙箱正在执行其他操作；可使用 jvm.*、js.*、status.process 或 terminate")
        try {
        check(session.process.isAlive) { "沙箱进程已退出，exit_code=${session.process.exitValue()}；使用 startup.logs" }
        if (command == "run") {
            val source = Path.of(args.text("artifact")).toRealPath()
            require(Files.isRegularFile(source) && Files.size(source) <= 256L * 1024 * 1024) { "产物必须为不超过 256 MiB 的 JAR/ZIP" }
            ZipFile(source.toFile()).use { zip ->
                require(zip.getEntry("META-INF/ToolsPlugin.txt") != null || zip.getEntry("pluginInfo.json") != null) { "产物根目录缺少插件入口" }
                var total = 0L
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement(); count++
                    require(entry.size >= 0) { "压缩条目长度未知" }
                    total += entry.size
                    require(total <= 512L * 1024 * 1024 && count <= 20000) { "产物解压体积或文件数超限" }
                    val relative = Path.of(entry.name).normalize()
                    require(!relative.isAbsolute && !relative.startsWith("..")) { "压缩包路径越界" }
                }
            }
            val copy = session.layout.root.resolve("artifacts/${java.util.UUID.randomUUID()}.zip")
            Files.copy(source, copy)
            args.addProperty("artifact", copy.toString())
        }
        return rpc(session, command, args)
        } finally { if (locked) session.operations.unlock() }
    }

    @Synchronized
    private fun start(owner: String, args: JsonObject): Any {
        val launcher = Path.of(args.text("launcher")).toRealPath()
        require(Files.isExecutable(launcher) && Files.isRegularFile(launcher)) { "launcher 必须是 IDE 可执行文件" }
        val baseVm = Path.of(args.text("vm_options")).toRealPath()
        require(Files.size(baseVm) < 1024 * 1024)
        val distribution = Path.of(args.text("jtools_distribution")).toRealPath()
        require(Files.isDirectory(distribution.resolve("lib"))) { "jtools_distribution 必须为含 lib 的解压后 JTools 发行目录" }
        val runtimeJar = Path.of(args.text("debugger_jar")).toRealPath()
        ZipFile(runtimeJar.toFile()).use { require(it.getEntry("META-INF/JToolsSandbox.txt") != null) { "debugger_jar 不含沙箱运行端" } }
        val parent = Path.of(args.text("sandbox_parent")).toRealPath()
        require(Files.isDirectory(parent))
        val fresh = args.get("fresh")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "fresh 必须为 boolean" }
            it.asBoolean
        } ?: false
        val launchKey = SandboxProfile.key(
            json.toJson(
                listOf(
                    owner,
                    launcher.toString(),
                    baseVm.toString(),
                    distribution.toString(),
                    runtimeJar.toString(),
                    parent.toString()
                )
            )
        )
        if (!fresh) {
            sessions.values.firstOrNull { it.owner == owner && it.launchKey == launchKey && it.profile != null && !it.closing && it.process.isAlive }
                ?.let {
                    return startResult(it, reused = true)
                }
        }
        val profile = if (fresh) null else SandboxProfile.acquire(parent, launchKey)
        try {
            val root = parent.resolve("jtools-sandbox-${java.util.UUID.randomUUID()}")
            val layout = SandboxLayout(root, profile?.root)
            Files.writeString(root.resolve("token"), layout.token)
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(
                    root,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                )
                Files.setPosixFilePermissions(
                    root.resolve("token"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                )
            }
            val pluginDir = root.resolve("plugins/jtools")
            copyDirectory(distribution, pluginDir)
            Files.copy(runtimeJar, pluginDir.resolve("lib/plugin-debugger-runtime.jar"))
            val ports = List(2) { ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) }
            val (port, cdpPort) = try {
                ports.map { it.localPort }
            } finally {
                ports.forEach { it.close() }
            }
            val vmFile = root.resolve("sandbox.vmoptions")
            Files.writeString(vmFile, layout.vmOptions(Files.readString(baseVm), port, cdpPort))
            val builder = ProcessBuilder(launcher.toString(), root.resolve("project").toString())
            builder.directory(root.toFile())
            val env = builder.environment()
            // 不继承 agent 注入和个人 IDE/JTools 配置；保留显示服务与 IDE 启动需要的基础环境。
            val retained = setOf(
                "PATH",
                "SystemRoot",
                "WINDIR",
                "DISPLAY",
                "WAYLAND_DISPLAY",
                "XDG_RUNTIME_DIR",
                "LANG",
                "LC_ALL",
                "TMPDIR"
            )
            val clean = env.filterKeys { it in retained }; env.clear(); env.putAll(clean)
            env["HOME"] = layout.homePath.toString()
            env["USERPROFILE"] = layout.homePath.toString()
            env["IDEA_VM_OPTIONS"] = vmFile.toString()
            env["WEBIDE_VM_OPTIONS"] = vmFile.toString()
            env["PYCHARM_VM_OPTIONS"] = vmFile.toString()
            env["GOLAND_VM_OPTIONS"] = vmFile.toString()
            builder.redirectErrorStream(true).redirectOutput(root.resolve("process.log").toFile())
            val process = builder.start()
            val session = Session(owner, layout, process, port, launchKey, profile)
            sessions[layout.id] = session
            process.onExit().thenRun { profile?.close() }
            return startResult(session, reused = false)
        } catch (error: Throwable) {
            profile?.close()
            throw error
        }
    }

    private fun startResult(session: Session, reused: Boolean): Map<String, Any> = mapOf(
        "sandbox_id" to session.layout.id,
        "state" to if (Files.exists(session.layout.root.resolve("endpoint"))) "READY" else "STARTING",
        "pid" to session.process.pid(), "root" to session.layout.root.toString(),
        "reused" to reused, "fresh" to (session.profile == null),
        "config_path" to session.layout.configPath.toString(),
        "note" to "先 ping 确认运行端；stop 后可 run 其他产物。终止后保留专用 IDE 配置，fresh=true 使用全新环境。不会自动接受许可协议。网络与文件系统不是 OS 安全隔离。",
    )

    private fun rpc(session: Session, command: String, args: JsonObject): Any {
        val endpoint = session.layout.root.resolve("endpoint")
        check(Files.isRegularFile(endpoint)) { SandboxStartupDiagnostics.pendingMessage(session.layout.root) }
        val port = Files.readString(endpoint).toInt()
        require(port in 1024..65535)
        val seconds = args.number("timeout_secs", 30)
        require(seconds in 1..300)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/rpc"))
            .timeout(Duration.ofSeconds(seconds.toLong()))
            .header("Authorization", "Bearer ${session.layout.token}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toJson(mapOf("command" to command, "arguments" to args))))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = JsonParser.parseString(response.body()).asJsonObject
        check(response.statusCode() == 200 && body.get("ok").asBoolean) { body.toString() }
        return body.get("result")
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "发行目录不能含符号链接: $path" }
                val dest = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
            }
        }
    }

    private fun readLog(path: Path, args: JsonObject): Any {
        val offset = args.get("offset")?.asLong ?: 0L
        require(offset >= 0)
        val size = Files.size(path)
        val buffer = java.nio.ByteBuffer.allocate(16000)
        Files.newByteChannel(path).use { it.position(offset.coerceAtMost(size)); it.read(buffer) }
        return mapOf(
            "offset" to offset,
            "next_offset" to offset + buffer.position(),
            "total_bytes" to size,
            "text" to String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        )
    }

    private fun terminate(session: Session) {
        session.closing = true
        synchronized(session) { session.debugger?.close() }
        val children = session.process.toHandle().descendants().use { it.toList() }
        children.reversed().forEach { it.destroy() }
        session.process.destroy()
        if (!session.process.waitFor(5, TimeUnit.SECONDS)) {
            children.reversed().forEach { if (it.isAlive) it.destroyForcibly() }
            session.process.destroyForcibly()
            check(session.process.waitFor(5, TimeUnit.SECONDS)) { "无法终止沙箱" }
        }
        session.profile?.close()
    }

    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        sessions.values.forEach {
            try {
                terminate(it)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        sessions.clear()
        failure?.let { throw it }
    }

    private fun help() = mapOf(
        "start" to "必填 launcher(IDE可执行文件), vm_options(基础VM配置), jtools_distribution(含lib的JTools安装或解压目录), debugger_jar, sandbox_parent(已存在目录)。默认复用当前项目会话同参数的存活沙箱；终止后复用专用IDE配置。fresh=true完全新建，可并行。只创建空测试项目，不复制个人配置或密钥。",
        "common" to "除start/help外必须 sandbox_id。每个沙箱绑定当前项目和会话。timeout_secs=1..300。",
        "run" to "artifact=JAR/ZIP绝对路径；必须先stop旧运行。先jvm.breakpoint可捕获初始化。",
        "lifecycle" to "ping, status, events(after游标), panel.open, panel.close(panel_id), stop, terminate(强制进程终止), status.process(含启动提示), startup.logs(offset字节偏移)。stop保留沙箱进程；terminate后start复用专用配置，fresh=true执行全新验收。",
        "methods" to "methods(object_id 或 class_name 二选一, method 可选过滤)。object_id 列实例方法，class_name 列静态方法；返回声明类、参数类型、返回类型、可见性及varargs。run/status返回plugin_object和project_object引用。继承/非public方法也列出，能否调用取决于JVM访问规则。",
        "invoke" to "invoke(object_id 或 class_name 二选一, method, parameter_types:[精确JVM类型名], arguments:[{value:JSON简单值或null}或{object_id:对象引用}], thread=worker/edt默认worker)。无参必须传两个空数组；数组类型写int[]/java.lang.String[]，数组value里的元素也使用value/object_id封装；varargs按数组传，不自动展开。不猜重载或隐式转换字符串为数字。对象结果返回kind=object/object_id/class_name，可继续methods/invoke；简单结果返回kind=value/value。对象引用只在本次run有效，stop后失效。使用插件ClassLoader，原始异常和堆栈返回。HTTP超时不会取消目标调用，禁止盲目重试；未完成操作仍占有沙箱锁，可用jvm.*调试恢复或terminate。",
        "swing" to "swing.snapshot(panel_id), swing.action(panel_id,ref,action,text/index), swing.screenshot(panel_id)。ref只在最近快照内有效；action=click/input/select/scroll。click校验后异步提交，返回operation_id及QUEUED；用swing.operation(operation_id)查询QUEUED/RUNNING/COMPLETED/FAILED，失败含error/stack。查询不等待EDT，可在断点暂停时使用；最多保留1000条操作记录，满后淘汰已结束记录。COMPLETED仅指按钮回调返回，不代表业务结果已验证。语义操作不是物理键鼠。",
        "js" to "js.cdp(panel_id,method,params,async=false) 默认同步；async=true 返回operation_id及QUEUED，通过js.operation(operation_id)查询QUEUED/RUNNING/COMPLETED/FAILED及result或error/stack。异步响应不因断点暂停超过20秒而失败，不为等待响应占用线程；首次调用仍需完成target绑定。COMPLETED只表示CDP成功响应，不代表业务结果已验证。查询不访问EDT或重新绑定target；面板关闭/stop使未完成操作失败，最终记录仍可查询。最多保留1000条操作，只淘汰已结束记录，满载未完成时拒绝新提交。不自动重发或宣称取消浏览器操作。js.events(panel_id,after)，js.screenshot(panel_id)。CDP支持Runtime/DOM/Input/Page/Network/Debugger，先enable域，再调用对应协议。事件仅保留最近1000条且不自动抓取网络凭据。",
        "jvm" to "jvm.breakpoint(class,line), jvm.exception(class,caught,uncaught), jvm.events(after), jvm.threads, jvm.frames(thread_id), jvm.variables(thread_id,frame), jvm.resume, jvm.pause, jvm.step(thread_id,depth=into/over/out), jvm.remove(request_id)。Kotlin使用编译后的类名与行号，不伪造完整表达式求值。",
        "security" to "独立进程和数据目录，不是OS级恶意代码沙箱；启动路径/发行目录由用户明确提供。不要输入日常密钥。"
    )
}
