package com.lhstack.tools.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.*
import com.lhstack.tools.plugins.pluginManager
import com.lhstack.tools.plugins.pluginState
import org.apache.commons.io.FileUtils
import org.jdesktop.swingx.VerticalLayout
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*


class SettingAction(windowPanel: SimpleToolWindowPanel, val project: Project) : AbstractPageAction(
    { "设置" },
    Icons.settingIcon(), windowPanel
) {
    private val sectionBorderColor = JBColor(Color(0xDFE3EA), Color(0x4C5052))
    private val sectionBackground = JBColor(Color(0xFBFCFE), Color(0x313335))
    private var panel: JPanel = JPanel()

    private var scrollPane: JScrollPane? = null

    init {

        panel.layout = VerticalLayout()
        panel.add(buildGeneralSettingsPanel())
        scrollPane = JBScrollPane(panel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
    }


    override fun getPanel(): JComponent {
        return scrollPane!!
    }

    private fun buildGeneralSettingsPanel(): JComponent {
        return JPanel(VerticalLayout(8)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 4, 10)
            add(groupTitle("基础设置", "插件基础行为与入口配置。"))
            add(buildPluginInstallPanel())
            add(buildConsolePanel())
            add(buildRepositoryPanel())
        }
    }

    private fun buildPluginInstallPanel(): JComponent {
        val textField = JBTextField(
            project.pluginState().pluginBasePath.replace("\\", "/")
        ).apply {
            toolTipText = project.pluginState().pluginBasePath.replace("\\", "/")
        }
        val chooseButton = JButton("选择目录").apply {
            preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
            addActionListener {
                project.chooseDirectory(
                    "选择插件安装目录",
                    VirtualFileManager.getInstance().findFileByUrl("file://${textField.toolTipText}")
                ) {
                    textField.text = it.presentableUrl
                    textField.toolTipText = it.presentableUrl
                    textField.revalidate()
                    textField.repaint()
                }
            }
        }
        val applyButton = JButton("应用").apply {
            preferredSize = Dimension(JBUI.scale(84), preferredSize.height)
            addActionListener {
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
        }

        return sectionPanel("插件安装目录", "默认目录为 ${Const.JTOOLS_PLUGIN_HOME}/plugins") {
            JPanel(GridBagLayout()).apply {
                isOpaque = false
                val c = GridBagConstraints().apply {
                    gridy = 0
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(2, 0, 0, 8)
                }
                c.gridx = 0
                c.weightx = 1.0
                add(textField, c)
                c.gridx = 1
                c.weightx = 0.0
                add(chooseButton, c)
                c.gridx = 2
                add(applyButton, c)
            }
        }
    }

    private fun buildConsolePanel(): JComponent {
        val consoleCheckBox = JCheckBox("启用日志控制台", project.pluginState().consoleLogEnabled).apply {
            toolTipText = "启用或禁用JTools日志控制台，禁用后可减少资源占用"
            addActionListener {
                project.pluginState().consoleLogEnabled = isSelected
                if (isSelected) {
                    project.activeConsolePanel()
                    project.infoNotify("日志控制台", "日志控制台已启用")
                } else {
                    project.deActiveConsolePanel()
                    project.infoNotify("日志控制台", "日志控制台已禁用")
                }
            }
        }
        return sectionPanel("日志控制台", "关闭后可减少资源占用") {
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(consoleCheckBox, BorderLayout.WEST)
            }
        }
    }

    private fun buildRepositoryPanel(): JComponent {
        return sectionPanel("插件仓库", "查看可用插件仓库") {
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(HyperlinkLabel("https://github.com/orgs/jtools-plugins/repositories").apply {
                    setHyperlinkTarget("https://github.com/orgs/jtools-plugins/repositories")
                }, BorderLayout.WEST)
                this.add(HyperlinkLabel("跳转").apply {
                    this.setHyperlinkTarget("https://github.com/orgs/jtools-plugins/repositories")
                })
            }
        }
    }

    private fun sectionPanel(title: String, hint: String? = null, contentFactory: () -> JComponent): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = sectionBackground
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(sectionBorderColor),
                JBUI.Borders.empty(10, 12)
            )
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(JLabel(title).apply {
//                        font = font.deriveFont((font.style or java.awt.Font.BOLD).toFloat())
                        hint?.let { toolTipText = it }
                    })
                    hint?.takeIf { it.isNotBlank() }?.let { add(helpLabel(title, it)) }
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(contentFactory(), BorderLayout.CENTER)
        }
    }

    private fun groupTitle(title: String, hint: String): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(JLabel(title))
            add(helpLabel(title, hint))
        }
    }

    private fun helpLabel(title: String, text: String): JLabel {
        return JLabel(Icons.helpIcon()).apply {
            toolTipText = text
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        project.infoNotify(title, text)
                    }
                }
            })
        }
    }

    private fun fieldLabel(text: String): JLabel {
        return JLabel("$text:").apply {
            border = JBUI.Borders.emptyRight(4)
        }
    }

}
