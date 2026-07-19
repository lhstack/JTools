package com.lhstack.tools.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class QueueEditingContractTest {
    @Test
    fun `only pending queue items are editable and expose complete prompts`() {
        val source = source("AgentChatPanel.kt")
        assertTrue(source.contains("require(queued.status == ChatQueueStatus.PENDING)"))
        assertTrue(source.contains("queued.prompt = normalizedPrompt"))
        assertTrue(source.contains("item.status == ChatQueueStatus.PENDING"))
        assertTrue(source.contains("item.prompt,"))
    }

    @Test
    fun `processing cancel targets active tools before the model response`() {
        val source = source("AgentChatPanel.kt")
        val stop = source.substringAfter("private fun stopQueueItem").substringBefore("private fun markQueueItemCancellationRequested")
        assertTrue(stop.contains("if (item.runningToolIds.isNotEmpty())"))
        assertTrue(stop.contains("item.toolToken.cancel()"))
        assertTrue(stop.contains("item.token.cancel()"))
        assertTrue(stop.indexOf("item.toolToken.cancel()") < stop.indexOf("item.token.cancel()"))
    }

    @Test
    fun `browser queue uses compact icon actions and horizontal overflow`() {
        val view = Files.readString(Path.of("agent-web/src/views/ChatView.vue"))
        val style = Files.readString(Path.of("agent-web/src/style.css"))
        assertTrue(view.contains("invoke('queue.edit'"))
        assertTrue(view.contains(":icon=\"EditPen\""))
        assertTrue(view.contains(":icon=\"item.processing ? VideoPause : Close\""))
        assertTrue(style.contains(".queue-panel{margin-top:7px;overflow-x:auto"))
        assertTrue(style.contains(".queue-strip{display:flex"))
        assertTrue(style.contains("flex:0 0 188px"))
        assertTrue(view.contains("characters.length > 5"))
        assertTrue(view.contains("item.prompt || '附件消息'"))
    }

    private fun source(file: String): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/$file"),
    )
}
