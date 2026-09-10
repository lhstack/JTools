package com.lhstack.tools.llm.provider

import com.google.gson.JsonElement
import com.lhstack.tools.llm.Message

data class ContinuationEvent(
    val eventType: String,
    val data: JsonElement,
)

data class ClaimedContinuationBatch(
    val batchId: String,
    val message: Message,
    val historyMessage: Message,
    val claimedEvents: List<ContinuationEvent> = emptyList(),
)

/**
 * 对齐 awake-claw `ModelContinuationPort`。
 * 运行中追加由仓储 claim / seal / mark_delivered，客户端不再直接 drain 内存队列。
 */
interface ModelContinuationPort {
    fun claimPending(): ClaimedContinuationBatch?
    fun claimOrSeal(): ClaimedContinuationBatch?
    fun markDelivered(batchId: String)

    companion object {
        val NONE: ModelContinuationPort = object : ModelContinuationPort {
            override fun claimPending(): ClaimedContinuationBatch? = null
            override fun claimOrSeal(): ClaimedContinuationBatch? = null
            override fun markDelivered(batchId: String) = Unit
        }
    }
}

fun AppendMessageChannel.asContinuationPort(): ModelContinuationPort = AppendMessageContinuation(this)

private class AppendMessageContinuation(
    private val channel: AppendMessageChannel,
) : ModelContinuationPort {
    override fun claimPending(): ClaimedContinuationBatch? = toBatch(channel.fetch())

    override fun claimOrSeal(): ClaimedContinuationBatch? {
        val messages = channel.fetchPendingOrClose() ?: return null
        return toBatch(messages)
    }

    override fun markDelivered(batchId: String) = Unit

    private fun toBatch(messages: List<AppendMessage>): ClaimedContinuationBatch? {
        if (messages.isEmpty()) return null
        val userMessage = Message.User(messages.flatMap { message ->
            buildList {
                if (message.content.isNotBlank()) add(com.lhstack.tools.llm.UserContent.Text(message.content))
                addAll(message.attachments)
            }
        })
        messages.forEach { channel.onInjected(listOf(it), 0) }
        channel.onProviderMessages(listOf(userMessage))
        return ClaimedContinuationBatch(
            batchId = messages.joinToString(",") { it.id },
            message = userMessage,
            historyMessage = userMessage,
        )
    }
}
