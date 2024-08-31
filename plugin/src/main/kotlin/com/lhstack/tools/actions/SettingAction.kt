package com.lhstack.tools.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBTextField
import com.lhstack.tools.const.Const
import com.lhstack.tools.ext.catch
import com.lhstack.tools.ext.chooseDirectory
import com.lhstack.tools.ext.fullMsg
import com.lhstack.tools.ext.substr
import com.lhstack.tools.plugins.pluginState
import org.apache.commons.io.FileUtils
import org.jdesktop.swingx.VerticalLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*


class SettingAction(windowPanel: SimpleToolWindowPanel, project: Project) : AbstractPageAction(
    { "设置" },
    IconLoader.findIcon("icons/setting.svg", SettingAction::class.java)!!, windowPanel
) {
    private var panel: JPanel = JPanel()

    init {

        panel.layout = VerticalLayout()
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            this.add(JLabel("插件安装目录: "))
            this.add(JLabel(IconLoader.findIcon("icons/help.svg", SettingAction::class.java)).apply {
                val toolTipText =
                    "修改插件安装的目录,默认安装目录为: ${
                        System.getProperty("user.home").replace("\\", "/")
                    }/.ideaTools/plugins"
                this.toolTipText = toolTipText
                this.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            Notifications.Bus.notify(
                                Notification(
                                    Const.TOOLS_WINDOW_ID,
                                    "提示",
                                    toolTipText,
                                    NotificationType.INFORMATION
                                ), project
                            )
                        }
                    }
                })
            })
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
                    project.chooseDirectory("选择插件安装目录") {
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
                                Notifications.Bus.notify(
                                    Notification(
                                        Const.TOOLS_WINDOW_ID,
                                        "提示",
                                        "插件目录不能是一个文件,请检查你输入的地址",
                                        NotificationType.ERROR
                                    ), project
                                )
                            }
                            //1. 拷贝插件到新目录
                            //2. 修改插件信息里面的安装目录
                            this.pluginState().plugins.forEach { (k, v) ->
                                try {
                                    val oldFile = File(v.path)
                                    val newFile = File(this.absolutePath,oldFile.name)
                                    FileUtils.copyFile(oldFile, newFile)
                                    v.path = newFile.absolutePath
                                    this.pluginState().plugins[k] = v
                                    java.nio.file.Files.delete(oldFile.toPath())
                                } catch (e: Throwable) {
                                    Notifications.Bus.notify(
                                        Notification(
                                            Const.TOOLS_WINDOW_ID,
                                            "迁移插件通知",
                                            "迁移插件失败,失败插件名称: ${v.name},插件版本: ${v.version},异常信息: ${e.fullMsg()}",
                                            NotificationType.INFORMATION
                                        ), project
                                    )
                                }
                            }
                            Notifications.Bus.notify(
                                Notification(
                                    Const.TOOLS_WINDOW_ID,
                                    "迁移插件通知",
                                    "迁移插件完毕",
                                    NotificationType.INFORMATION
                                ), project
                            )
                            this.pluginState().pluginBasePath = this.absolutePath.replace("\\", "/")
                        }
                    }
                }
            })
        })
        val jSeparator = JSeparator(SwingConstants.HORIZONTAL)
        panel.add(jSeparator)
    }

    override fun getPanel(): JComponent {
        return panel
    }

}