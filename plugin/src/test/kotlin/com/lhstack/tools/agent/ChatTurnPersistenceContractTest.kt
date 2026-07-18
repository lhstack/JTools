package com.lhstack.tools.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTurnPersistenceContractTest {
    @Test
    fun `queue receives model log id when log is created`() {
        val source = source("model/provider/AgentRuntime.kt")
        assertTrue(source.contains("onLogCreated = request.onLogCreated"))
        assertTrue(source("AgentChatPanel.kt").contains("onLogCreated = { logId -> item.persistedLogId = logId }"))
    }

    @Test
    fun `model log management deletion does not reject chat turns`() {
        val source = source("model/log/ModelLogService.kt")
        assertFalse(source.contains("聊天对话日志属于会话记录，不能在模型日志中删除"))
    }

    private fun source(relative: String): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/$relative"),
    )
}
