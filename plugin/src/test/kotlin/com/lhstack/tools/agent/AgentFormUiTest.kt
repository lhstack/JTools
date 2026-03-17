package com.lhstack.tools.agent

import com.intellij.icons.AllIcons
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentFormUiTest {

    @Test
    fun `openai compatible provider exposes deepseek template`() {
        val templates = AgentFormUi.vendorTemplatesFor(AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE)

        assertTrue(templates.any { it.id == AgentProviderCatalog.TEMPLATE_DEEPSEEK })
    }

    @Test
    fun `anthropic provider exposes only anthropic template`() {
        val templates = AgentFormUi.vendorTemplatesFor(AgentProviderCatalog.TYPE_ANTHROPIC)

        assertEquals(listOf(AgentProviderCatalog.TEMPLATE_ANTHROPIC), templates.map { it.id })
    }

    @Test
    fun `form row renders help icon without bottom hint label`() {
        val row = AgentFormUi.row("名称", JLabel("field"), "供应方显示名称。")

        val titlePanel = row.getComponent(0) as JPanel
        val titleLabel = titlePanel.components.filterIsInstance<JLabel>().firstOrNull { it.text == "名称" }
        val helpLabel = titlePanel.components.filterIsInstance<JLabel>().firstOrNull { it.icon == AllIcons.General.ContextHelp }

        assertNotNull(titleLabel)
        assertFalse(titleLabel.toolTipText?.isNotBlank() == true)
        assertNotNull(helpLabel)
        assertEquals("供应方显示名称。", helpLabel.toolTipText)
        assertEquals(2, row.componentCount)
    }

    @Test
    fun `section card renders title and content in dedicated panel`() {
        val content = JLabel("content")
        val card = AgentFormUi.sectionCard("基础配置", "说明文案", content)

        val labels = collectComponents(card).filterIsInstance<JLabel>()

        assertTrue(labels.any { it.text == "基础配置" })
        assertTrue(labels.any { it.icon == AllIcons.General.ContextHelp })
        assertTrue(collectComponents(card).contains(content))
    }

    @Test
    fun `centered column constrains preferred width`() {
        val content = JPanel().apply {
            preferredSize = Dimension(1200, 240)
        }

        val wrapper = AgentFormUi.centeredColumn(720, content)

        assertEquals(720, content.maximumSize.width)
        assertTrue(wrapper.components.contains(content))
    }

    @Test
    fun `constrain width keeps fields shrinkable`() {
        val content = JPanel().apply {
            preferredSize = Dimension(1200, 48)
            minimumSize = Dimension(0, 48)
        }

        AgentFormUi.constrainWidth(content, 520)

        assertEquals(520, content.preferredSize.width)
        assertEquals(520, content.maximumSize.width)
        assertEquals(0, content.minimumSize.width)
    }

    private fun collectComponents(container: Container): List<java.awt.Component> {
        return container.components.flatMap { component ->
            listOf(component) + if (component is Container) collectComponents(component) else emptyList()
        }
    }
}
