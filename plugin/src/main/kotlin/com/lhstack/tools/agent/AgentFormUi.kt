package com.lhstack.tools.agent

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

data class AgentSelectOption(
    val id: String,
    val label: String,
    val description: String,
    val group: String = "",
)

object AgentFormUi {
    private val cardBorderColor = JBColor(Color(0xD7DCE3), Color(0x4C5052))
    private val cardBackground = JBColor(Color(0xFBFCFE), Color(0x313335))

    val providerTypes = listOf(
        AgentSelectOption(AgentProviderCatalog.TYPE_DASHSCOPE, "DashScope", "阿里云百炼原生接入。"),
        AgentSelectOption(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            "OpenAI-Compatible",
            "兼容 OpenAI Chat Completions 的供应商。"
        ),
        AgentSelectOption(AgentProviderCatalog.TYPE_ANTHROPIC, "Anthropic", "Claude 原生接口。"),
        AgentSelectOption(AgentProviderCatalog.TYPE_GEMINI, "Gemini", "Google Gemini 原生接口。"),
        AgentSelectOption(AgentProviderCatalog.TYPE_OLLAMA, "Ollama", "本地 Ollama 服务。"),
    )

    private val vendorTemplates = listOf(
        template(
            AgentProviderCatalog.TYPE_DASHSCOPE,
            AgentProviderCatalog.TEMPLATE_DASHSCOPE,
            "DashScope",
            "DashScope 原生模板。"
        ),
        template(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            AgentProviderCatalog.TEMPLATE_OPENAI,
            "OpenAI",
            "标准 OpenAI 兼容格式。"
        ),
        template(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            AgentProviderCatalog.TEMPLATE_DEEPSEEK,
            "DeepSeek",
            "DeepSeek 兼容模板。"
        ),
        template(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            AgentProviderCatalog.TEMPLATE_GLM,
            "GLM",
            "智谱 GLM 兼容模板。"
        ),
        template(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            AgentProviderCatalog.TEMPLATE_OPENROUTER,
            "OpenRouter",
            "OpenRouter 聚合网关模板。"
        ),
        template(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            AgentProviderCatalog.TEMPLATE_SILICONFLOW,
            "SiliconFlow",
            "硅基流动兼容模板。"
        ),
        template(
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            AgentProviderCatalog.TEMPLATE_ALIBABA_OPENAI_COMPATIBLE,
            "Alibaba Compatible",
            "阿里云 OpenAI 兼容网关模板。"
        ),
        template(
            AgentProviderCatalog.TYPE_ANTHROPIC,
            AgentProviderCatalog.TEMPLATE_ANTHROPIC,
            "Anthropic",
            "Anthropic 原生模板。"
        ),
        template(AgentProviderCatalog.TYPE_GEMINI, AgentProviderCatalog.TEMPLATE_GEMINI, "Gemini", "Gemini 原生模板。"),
        template(AgentProviderCatalog.TYPE_OLLAMA, AgentProviderCatalog.TEMPLATE_OLLAMA, "Ollama", "Ollama 原生模板。"),
    )

    fun providerTypeOptions(): Array<AgentSelectOption> = providerTypes.toTypedArray()

    fun vendorTemplatesFor(providerType: String): List<AgentSelectOption> {
        return vendorTemplates.filter { it.group == providerType }
    }

    fun providerTypeLabel(providerType: String): String {
        return providerTypes.firstOrNull { it.id == providerType }?.label ?: providerType
    }

    fun vendorTemplateLabel(vendorTemplate: String): String {
        return vendorTemplates.firstOrNull { it.id == vendorTemplate }?.label ?: vendorTemplate
    }

    fun legacyTypeFor(providerType: String): String {
        return if (providerType == AgentProviderCatalog.TYPE_ANTHROPIC) {
            AgentProviderType.ANTHROPIC.id
        } else {
            AgentProviderType.OPENAI.id
        }
    }

    fun row(label: String, field: JComponent, hint: String? = null): JPanel {
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(8)
            add(JLabel(label))
            hint?.takeIf { it.isNotBlank() }?.let {
                add(JLabel(AllIcons.General.ContextHelp).apply {
                    toolTipText = it
                })
            }
        }
        titlePanel.preferredSize = Dimension(JBUI.scale(150), titlePanel.preferredSize.height)
        return JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(2, 0)
            add(titlePanel, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
    }

    fun sectionCard(title: String, hint: String? = null, content: JComponent): JPanel {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
            }, BorderLayout.WEST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(content, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            this.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                this.border = JBUI.Borders.empty(8, 0)
                this.add(JLabel(title))
                hint?.takeIf { it.isNotBlank() }?.let {
                    add(JLabel(AllIcons.General.ContextHelp).apply {
                        toolTipText = it
                    })
                }
            }, BorderLayout.NORTH)
            this.add(JPanel(BorderLayout(0, JBUI.scale(10))).apply {
                isOpaque = true
                background = cardBackground
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(cardBorderColor, 1, true),
                    JBUI.Borders.empty(14)
                )
                add(header, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }

    fun fieldTile(label: String, field: JComponent, hint: String? = null): JPanel {
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JLabel(label))
                hint?.takeIf { it.isNotBlank() }?.let {
                    add(JLabel(AllIcons.General.ContextHelp).apply {
                        toolTipText = it
                    })
                }
            }, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }
    }

    fun twoColumnGrid(vararg fields: JComponent): JPanel {
        return JPanel(GridLayout(0, 2, JBUI.scale(12), JBUI.scale(12))).apply {
            isOpaque = false
            fields.forEach { add(it) }
        }
    }

    fun verticalStack(vararg components: JComponent): JPanel {
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            components.forEachIndexed { index, component ->
                add(component)
            }
        }
    }

    fun constrainWidth(component: JComponent, width: Int) {
        val scaledWidth = JBUI.scale(width)
        val preferred = component.preferredSize
        val minimum = component.minimumSize
        component.preferredSize = Dimension(scaledWidth, preferred.height)
        component.maximumSize = Dimension(scaledWidth, component.maximumSize.height.coerceAtLeast(preferred.height))
        component.minimumSize = Dimension(0, minimum.height)
    }

    private fun template(providerType: String, id: String, label: String, description: String): AgentSelectOption {
        return AgentSelectOption(id, label, description, providerType)
    }

    fun templateValue(option: AgentSelectOption): String {
        return option.id
    }
}
