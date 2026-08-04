package com.lhstack.tools.agent.model.provider

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.UserContent
import java.util.UUID

/** 用户在模型运行期间追加的一条消息。 */
data class AppendMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val createdAt: String,
    val attachments: List<UserContent> = emptyList(),
    val attachmentSnapshots: List<JsonObject> = emptyList(),
)

/** 已投递追加消息及其在 provider 回环中的精确位置。 */
data class InjectedAppendMessage(
    val message: AppendMessage,
    val round: Int,
)

/**
 * 追加消息通道。fetch 以 drain 语义一次取出全部待投递消息；fetchPendingOrClose 在同一个临界区内：
 * 有消息时取出并保持通道开放，无消息时关闭通道并返回 null，避免最终回复与追加消息之间的竞态。
 */
interface AppendMessageChannel {
    fun fetch(): List<AppendMessage>
    fun fetchPendingOrClose(): List<AppendMessage>?
    fun onInjected(messages: List<AppendMessage>, round: Int) = Unit
    fun onProviderMessages(messages: List<Message>) = Unit

    companion object {
        val NONE: AppendMessageChannel = object : AppendMessageChannel {
            override fun fetch(): List<AppendMessage> = emptyList()
            override fun fetchPendingOrClose(): List<AppendMessage>? = null
        }
    }
}

internal class RecordingAppendMessageChannel(private val delegate: AppendMessageChannel) : AppendMessageChannel {
    private val lock = Any()
    private val injected = mutableListOf<InjectedAppendMessage>()
    private val providerMessages = mutableListOf<Message>()

    override fun fetch(): List<AppendMessage> = delegate.fetch()
    override fun fetchPendingOrClose(): List<AppendMessage>? = delegate.fetchPendingOrClose()

    override fun onInjected(messages: List<AppendMessage>, round: Int) {
        synchronized(lock) { messages.forEach { injected.add(InjectedAppendMessage(it, round)) } }
        delegate.onInjected(messages, round)
    }

    override fun onProviderMessages(messages: List<Message>) {
        synchronized(lock) { providerMessages.addAll(messages) }
        delegate.onProviderMessages(messages)
    }

    fun appendMessagesSnapshot(): List<InjectedAppendMessage> = synchronized(lock) { injected.toList() }
    fun providerMessagesSnapshot(): List<Message> = synchronized(lock) { providerMessages.toList() }
}

internal fun AppendMessage.toUserMessage(): Message = Message.User(buildList {
    if (content.isNotBlank()) add(UserContent.Text(content))
    addAll(attachments)
})

/** 供模型日志和浏览器卡片共用的稳定 JSON 契约。 */
internal fun InjectedAppendMessage.toJson(): JsonObject = JsonObject().apply {
    addProperty("id", message.id)
    addProperty("content", message.content)
    add("attachments", com.google.gson.JsonArray().apply {
        message.attachmentSnapshots.forEach { add(it.deepCopy()) }
    })
    addProperty("created_at", message.createdAt)
    addProperty("injected_round", round)
}
