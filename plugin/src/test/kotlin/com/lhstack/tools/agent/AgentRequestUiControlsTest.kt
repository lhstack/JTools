package com.lhstack.tools.agent

import com.intellij.openapi.actionSystem.AnAction
import org.junit.jupiter.api.Test
import javax.swing.JComboBox
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRequestUiControlsTest {

    @Test
    fun `request state also locks and restores system prompt controls`() {
        val sessionSelector = JComboBox<String>()
        val providerSelector = JComboBox<String>()
        val systemPromptSelector = JComboBox<String>()
        val modelSelector = JComboBox<String>()
        val conversationModeSelector = JComboBox<String>()
        val sendAction = noopAction()
        val stopAction = noopAction()
        val providerManageAction = noopAction()
        val systemPromptManageAction = noopAction()
        val modelManageAction = noopAction()
        val modelSettingsAction = noopAction()
        val skillSelectAction = noopAction()
        val skillManageAction = noopAction()
        val mcpManageAction = noopAction()
        val actionState = mutableMapOf<AnAction, Boolean>()
        var inputEnabled = true

        val controls = AgentRequestUiControls(
            sessionSelector = sessionSelector,
            providerSelector = providerSelector,
            systemPromptSelector = systemPromptSelector,
            modelSelector = modelSelector,
            conversationModeSelector = conversationModeSelector,
            sendAction = sendAction,
            stopAction = stopAction,
            providerManageAction = providerManageAction,
            systemPromptManageAction = systemPromptManageAction,
            modelManageAction = modelManageAction,
            modelSettingsAction = modelSettingsAction,
            skillSelectAction = skillSelectAction,
            skillManageAction = skillManageAction,
            mcpManageAction = mcpManageAction,
        )

        controls.applyRequestInProgress(true, setActionEnabled = { action, enabled ->
            actionState[action] = enabled
        }, setInputEnabled = { enabled ->
            inputEnabled = enabled
        })

        assertFalse(systemPromptSelector.isEnabled)
        assertEquals(false, actionState[systemPromptManageAction])
        assertFalse(inputEnabled)

        controls.applyRequestInProgress(false, setActionEnabled = { action, enabled ->
            actionState[action] = enabled
        }, setInputEnabled = { enabled ->
            inputEnabled = enabled
        })

        assertTrue(systemPromptSelector.isEnabled)
        assertEquals(true, actionState[systemPromptManageAction])
        assertTrue(inputEnabled)
    }

    private fun noopAction(): AnAction {
        return object : AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = Unit
        }
    }
}
