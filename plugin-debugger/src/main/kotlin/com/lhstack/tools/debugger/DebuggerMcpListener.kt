package com.lhstack.tools.debugger

import com.intellij.util.messages.Topic

internal fun interface DebuggerMcpListener {
    fun stateChanged(state: DebuggerMcpServer.State)

    companion object {
        val TOPIC = Topic.create("JTools debugger application MCP state", DebuggerMcpListener::class.java)
    }
}
