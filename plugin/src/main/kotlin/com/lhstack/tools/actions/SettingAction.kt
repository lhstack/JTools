package com.lhstack.tools.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.*
import com.lhstack.tools.agent.AgentProviderConfigPanel
import com.lhstack.tools.plugins.pluginManager
import com.lhstack.tools.plugins.pluginState
import org.apache.commons.io.FileUtils
import org.jdesktop.swingx.VerticalLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import com.intellij.openapi.ui.DialogWrapper


class SettingAction(windowPanel: SimpleToolWindowPanel, val project: Project) : AbstractPageAction(
    { "设置" },
    Icons.settingIcon(), windowPanel
) {
    private var panel: JPanel = JPanel()
    private var pluginInstallDirHelpLabel: JLabel = JLabel(Icons.helpIcon()).apply {
        val toolTipText =
            "修改插件安装的目录,默认安装目录为: ${Const.JTOOLS_PLUGIN_HOME}/plugins"
        this.toolTipText = toolTipText
        this.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    project.infoNotify("提示", toolTipText)
                }
            }
        })
    }

    init {

        panel.layout = VerticalLayout()

        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            this.add(JLabel("插件安装目录: "))
            this.add(pluginInstallDirHelpLabel)
        })
        //F:\Repo\Gradle\caches\modules-2\files-2.1\cn.dorck.code.guarder
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            val textField = JBTextField(
                this.pluginState().pluginBasePath.replace("\\", "/").substr(0, 30) { s -> "$s..." }
            )
            textField.toolTipText = this.pluginState().pluginBasePath.replace("\\", "/")
            this.add(textField)
            this.add(JButton("选择目录").apply {
                this.addActionListener {
                    project.chooseDirectory(
                        "选择插件安装目录",
                        VirtualFileManager.getInstance().findFileByUrl("file://${textField.toolTipText}")
                    ) {
                        textField.text = it.presentableUrl.substr(0, 30) { s -> "$s..." }
                        textField.toolTipText = it.presentableUrl
                        textField.revalidate()
                        textField.repaint()
                    }
                }
            })
            this.add(JButton("应用").apply {
                this.addActionListener {
                    File(textField.toolTipText).catch {
                        val originPluginDir = File(this.pluginState().pluginBasePath)
                        if (this.absolutePath != originPluginDir.absolutePath) {
                            if (!this.exists()) {
                                this.mkdirs()
                            }
                            if (this.isFile) {
                                project.errorNotify("提示", "插件目录不能是一个文件,请检查你输入的地址")
                            }
                            //1. 拷贝插件到新目录
                            //2. 修改插件信息里面的安装目录
                            this.pluginState().plugins.forEach { (k, v) ->
                                try {
                                    val classloader = this.pluginManager().classloaders.remove(v)
                                    val oldFile = File(v.path)
                                    val newFile = File(this.absolutePath, oldFile.name)
                                    if (v.type.equalsAnyIgnoreCase("js")) {
                                        FileUtils.copyDirectory(oldFile, newFile)
                                    } else {
                                        FileUtils.copyFile(oldFile, newFile)
                                    }
                                    v.path = newFile.absolutePath
                                    this.pluginState().plugins[k] = v

                                    this.pluginManager().classloaders[v] = classloader!!
                                    classloader.reset(arrayListOf(newFile.toPath()))
                                    oldFile.forceDelete()
                                } catch (e: Throwable) {
                                    project.errorNotify(
                                        "迁移插件通知",
                                        "迁移插件失败,失败插件名称: ${v.name},插件版本: ${v.version},异常信息: ${e.fullMsg()}"
                                    )
                                }
                            }
                            project.infoNotify("迁移插件通知", "迁移插件完毕")
                            this.pluginState().pluginBasePath = this.absolutePath.replace("\\", "/")
                        }
                    }
                }
            })
        })
        panel.add(JSeparator(SwingConstants.HORIZONTAL))
        
        // 日志控制台启用/禁用设置
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            this.add(JLabel("日志控制台: "))
            val consoleCheckBox = JCheckBox("启用日志控制台", this.pluginState().consoleLogEnabled)
            consoleCheckBox.toolTipText = "启用或禁用JTools日志控制台，禁用后可减少资源占用"
            consoleCheckBox.addActionListener {
                this.pluginState().consoleLogEnabled = consoleCheckBox.isSelected
                if (consoleCheckBox.isSelected) {
                    project.activeConsolePanel()
                    project.infoNotify("日志控制台", "日志控制台已启用")
                } else {
                    project.deActiveConsolePanel()
                    project.infoNotify("日志控制台", "日志控制台已禁用")
                }
            }
            this.add(consoleCheckBox)
        })
        panel.add(JSeparator(SwingConstants.HORIZONTAL))

        // 智能体供应方设置
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            this.add(JLabel("智能体设置: "))
            this.add(JLabel("支持多供应方"))
        })
        val providerManageButton = JButton("供应方管理")
        val maxIterationsField = JBTextField(this.pluginState().agentMaxToolIterations.toString()).apply {
            columns = 6
            toolTipText = "最大工具调用轮次(正整数, 默认30)"
            isEditable = true
        }
        val toolTimeoutField = JBTextField(this.pluginState().agentToolTimeoutMs.toString()).apply {
            columns = 8
            toolTipText = "单次工具调用超时(毫秒, 默认120000)"
            isEditable = true
        }
        providerManageButton.addActionListener {
            openProviderManager()
        }

        val agentForm = JPanel(GridBagLayout())
        val labelInsets = JBUI.insets(2, 0, 2, 8)
        val fieldInsets = JBUI.insets(2, 0, 2, 0)
        val constraints = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        constraints.insets = labelInsets
        agentForm.add(JLabel("  供应方: "), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.insets = fieldInsets
        agentForm.add(providerManageButton, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        constraints.weightx = 0.0
        constraints.insets = labelInsets
        agentForm.add(JLabel("  Max Tool Iterations: "), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.insets = fieldInsets
        agentForm.add(maxIterationsField, constraints)

        constraints.gridx = 0
        constraints.gridy = 2
        constraints.weightx = 0.0
        constraints.insets = labelInsets
        agentForm.add(JLabel("  Tool Timeout (ms): "), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.insets = fieldInsets
        agentForm.add(toolTimeoutField, constraints)

        panel.add(agentForm)
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            this.add(JButton("应用").apply {
                this.addActionListener {
                    val maxIterations = maxIterationsField.text.trim().toIntOrNull()
                    if (maxIterations != null && maxIterations > 0) {
                        this.pluginState().agentMaxToolIterations = maxIterations
                    }
                    val toolTimeoutMs = toolTimeoutField.text.trim().toIntOrNull()
                    if (toolTimeoutMs != null && toolTimeoutMs > 0) {
                        this.pluginState().agentToolTimeoutMs = toolTimeoutMs
                    }
                    project.infoNotify("智能体设置", "已保存智能体配置")
                }
            })
        })
        panel.add(JSeparator(SwingConstants.HORIZONTAL))

        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            this.add(JLabel("插件仓库: "))
            this.add(HyperlinkLabel("跳转").apply {
                this.setHyperlinkTarget("https://github.com/orgs/jtools-plugins/repositories")
            })
        })
    }

    private fun openProviderManager() {
        val dialog = object : DialogWrapper(project, false) {
            private val panel = AgentProviderConfigPanel(project)

            init {
                title = "供应方配置"
                setSize(JBUI.scale(980), JBUI.scale(520))
                init()
            }

            override fun createCenterPanel(): JComponent = panel.component

            override fun createActions(): Array<out Action?> = arrayOf()
        }
        dialog.showAndGet()
    }

    override fun getPanel(): JComponent {
        return panel
    }

}
