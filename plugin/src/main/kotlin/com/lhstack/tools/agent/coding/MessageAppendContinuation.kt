package com.lhstack.tools.agent.coding

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lhstack.tools.db.service.MessageStoreService
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.provider.ClaimedContinuationBatch
import com.lhstack.tools.llm.provider.ContinuationEvent
import com.lhstack.tools.llm.provider.ModelContinuationPort

class MessageAppendContinuation(
    private val sessionId: Long,
    private val turnId: String,
    private val modalities: Collection<String> = emptyList(),
) : ModelContinuationPort {
    override fun claimPending(): ClaimedContinuationBatch? = claim(sealIfEmpty = false)

    override fun claimOrSeal(): ClaimedContinuationBatch? = claim(sealIfEmpty = true)

    override fun markDelivered(batchId: String) {
        val ids = com.google.gson.JsonParser.parseString(batchId).asJsonArray.map { it.asLong }
        MessageStoreService.deliverAppendItems(ids)
    }

    private fun claim(sealIfEmpty: Boolean): ClaimedContinuationBatch? {
        val items = MessageStoreService.claimAppendItems(sessionId, turnId, sealIfEmpty)
        if (items.isEmpty()) return null
        val content = items.joinToString("\n") { it.content }.ifBlank { items.first().content }
        val attachmentIds = items.flatMap { it.attachments }.distinct()
        val attachments = MessageStoreService.listAttachmentsByIds(sessionId, attachmentIds)
        val userMessage = CodingAttachmentPrompt.currentUserMessage(content, attachments, modalities)
        val events = items.map { item ->
            ContinuationEvent(
                eventType = "append_claimed",
                data = JsonObject().apply {
                    addProperty("id", item.id)
                    addProperty("content", item.content)
                    add("attachments", JsonArray().apply { item.attachments.forEach(::add) })
                },
            )
        }
        return ClaimedContinuationBatch(
            batchId = JsonArray().apply { items.forEach { add(it.id) } }.toString(),
            message = userMessage,
            historyMessage = userMessage,
            claimedEvents = events,
        )
    }
}
