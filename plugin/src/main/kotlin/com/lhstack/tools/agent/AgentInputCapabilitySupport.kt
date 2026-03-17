package com.lhstack.tools.agent

object AgentInputCapabilitySupport {
    private val nonTextCapabilities = setOf("image", "audio", "video", "file")

    fun attachmentButtonVisible(modelSettings: AgentModelSettings?): Boolean {
        return modelSettings?.modelCapabilities.orEmpty().any { it in nonTextCapabilities }
    }

    fun allowedAttachmentKinds(modelSettings: AgentModelSettings?): Set<String> {
        return modelSettings?.modelCapabilities.orEmpty()
            .filter { it in nonTextCapabilities }
            .toSet()
    }
}
