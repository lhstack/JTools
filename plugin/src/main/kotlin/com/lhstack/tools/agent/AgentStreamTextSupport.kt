package com.lhstack.tools.agent

object AgentStreamTextSupport {
    fun delta(previous: String, current: String): String {
        if (current.isEmpty()) {
            return ""
        }
        if (previous.isEmpty()) {
            return current
        }
        return if (current.startsWith(previous)) {
            current.removePrefix(previous)
        } else if (current == previous) {
            ""
        } else {
            current
        }
    }
}
