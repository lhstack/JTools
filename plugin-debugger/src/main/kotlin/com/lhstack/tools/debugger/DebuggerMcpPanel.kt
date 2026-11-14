package com.lhstack.tools.debugger

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.*

/** A view of the application service. Closing a panel only unsubscribes its state listener. */
internal class DebuggerMcpPanel(private val service: DebuggerMcpServer, private val jar: () -> Path) : JPanel(BorderLayout(8, 8)) {
    private val port = JSpinner(SpinnerNumberModel(0, 0, 65535, 1))
    private val start = JButton("启动")
    private val stop = JButton("停止服务")
    private val newClient = JButton("新客户端配置")
    private val protocols = listOf("Streamable HTTP", "SSE", "stdio")
    private val outputs = protocols.associateWith { protocol ->
        JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; name = "debugger-mcp-$protocol" }
    }
    private val tabs = JTabbedPane().apply {
        protocols.forEach { protocol -> addTab(protocol, JScrollPane(outputs.getValue(protocol))) }
    }
    private val copy = JButton("复制配置")
    private val status = JLabel()
    private var subscription: com.intellij.util.messages.MessageBusConnection? = null
    private var lastRevision = -1L
    private var attached = false
    private var working = false

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val actions = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("MCP 端口（0 自动分配）")); add(port)
            add(start); add(stop); add(newClient)
            add(copy)
            add(status)
        }
        copy.addActionListener {
            val selected = outputs.getValue(protocols[tabs.selectedIndex])
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(selected.text), null)
        }
        start.addActionListener {
            val requestedPort = port.value as Int
            background { service.start(requestedPort) }
        }
        stop.addActionListener { background { service.close() } }
        newClient.addActionListener { background { service.newClient() } }
        add(actions, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        render(service.state())
    }

    override fun addNotify() {
        super.addNotify()
        attached = true
        val connection = service.messageBus.connect()
        connection.subscribe(DebuggerMcpListener.TOPIC, DebuggerMcpListener { state ->
            SwingUtilities.invokeLater { if (attached) render(state) }
        })
        subscription = connection
        render(service.state())
    }

    override fun removeNotify() {
        attached = false
        subscription?.disconnect()
        subscription = null
        super.removeNotify()
    }

    private fun render(state: DebuggerMcpServer.State) {
        if (state.revision < lastRevision) return
        lastRevision = state.revision
        status.text = if (state.running) "运行中 · 127.0.0.1:${state.port}" else "已停止"
        port.isEnabled = !state.running && !working
        if (state.running) port.value = state.port
        start.isEnabled = !state.running && !working
        stop.isEnabled = state.running && !working
        newClient.isEnabled = state.running && !working
        copy.isEnabled = state.running
        val configurations = if (state.running) configurations(state) else emptyMap()
        outputs.forEach { (protocol, output) ->
            output.text = configurations[protocol] ?: "应用级 MCP 服务已停止。所有项目和会话共享此服务，启动后各面板自动同步配置。\n停止服务保留沙箱与客户端令牌；关闭面板或项目不会停止共享服务。卸载 debugger 或退出 IDE 会清理服务与沙箱。"
            output.caretPosition = 0
        }
    }

    private fun configurations(state: DebuggerMcpServer.State): Map<String, String> {
        val base = "http://127.0.0.1:${state.port}"
        val token = checkNotNull(state.token)
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val configurations = mapOf(
            "Streamable HTTP" to mapOf("type" to "http", "url" to "$base/mcp", "headers" to mapOf("Authorization" to "Bearer $token")),
            "SSE" to mapOf("type" to "sse", "url" to "$base/sse", "headers" to mapOf("Authorization" to "Bearer $token")),
            "stdio" to mapOf("command" to java, "args" to listOf("-jar", jar().toRealPath().toString(), "--url", "$base/mcp"), "env" to mapOf("JTOOLS_DEBUGGER_TOKEN" to token)))
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        return configurations.mapValues { (_, configuration) ->
            gson.toJson(mapOf("mcpServers" to mapOf("jtools-debugger" to configuration)))
        }
    }

    private fun background(action: () -> Unit) {
        working = true
        render(service.state())
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                val worker = Thread.currentThread()
                val previous = worker.contextClassLoader
                try {
                    worker.contextClassLoader = javaClass.classLoader
                    action()
                } finally { worker.contextClassLoader = previous }
            }
            override fun done() {
                working = false
                render(service.state())
                try { get() }
                catch (error: Exception) {
                    copy.isEnabled = false
                    outputs.getValue(protocols[tabs.selectedIndex]).text = (error.cause ?: error).stackTraceToString()
                }
            }
        }.execute()
    }
}
