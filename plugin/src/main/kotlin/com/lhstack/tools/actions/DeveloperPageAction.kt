package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.panels.VerticalLayout
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.chooseDirectory
import com.lhstack.tools.ext.substr
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.pluginManager
import org.apache.commons.lang3.StringUtils
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class DeveloperPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件开发调试" }, Icons.DEVELOPER_ICON, windowPanel) {

    private val panel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true)

    private val contentPanel: JPanel = JPanel(BorderLayout())

    private val contentPluginDirLabel = JLabel("当前无插件目录", JLabel.CENTER)

    private val contentPluginResourceLabel = JLabel("当前无插件资源目录", JLabel.CENTER)

    private val contentPluginPanel = JPanel(BorderLayout())

    //插件classpath目录
    private val pluginClasspath = AtomicReference<String>()

    //插件resource资源目录
    private val pluginResourcePath = AtomicReference<String>()

    private val pluginInstance = AtomicReference<IPlugin>()

    init {
        val actionGroup = DefaultActionGroup()
        initActionGroup(actionGroup)
        val actionToolbar = ActionManager.getInstance().createActionToolbar("DeveloperPage@Toolbar", actionGroup, true)
        actionToolbar.targetComponent = panel
        actionToolbar.component.alignmentX = JComponent.RIGHT_ALIGNMENT
        panel.toolbar = actionToolbar.component
        val topPanel = JPanel(VerticalLayout(2))
        topPanel.add(contentPluginDirLabel)
        topPanel.add(contentPluginResourceLabel)
        contentPanel.add(topPanel, BorderLayout.NORTH)
        contentPanel.add(contentPluginPanel, BorderLayout.CENTER)
        panel.setContent(contentPanel)
    }

    private fun initActionGroup(actionGroup: DefaultActionGroup) {
        actionGroup.add(object : AnAction({ "选择插件编译后的class目录" }, Icons.SELECT_ICON) {
            override fun actionPerformed(e: AnActionEvent) {
                project.chooseDirectory("选择插件编译后的class目录") {
                    pluginClasspath.set(it.presentableUrl)
                    contentPluginDirLabel.text = "当前插件目录: ${it.presentableUrl}".substr(0, 60) + "..."
                    contentPluginDirLabel.toolTipText = "当前插件目录: ${it.presentableUrl}"
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

        })

        actionGroup.add(object : AnAction({ "选择插件编译后的resource目录" }, Icons.RESOURCE_ICON) {
            override fun actionPerformed(e: AnActionEvent) {
                project.chooseDirectory("选择插件编译后的resource目录") {
                    pluginResourcePath.set(it.presentableUrl)
                    contentPluginResourceLabel.text = "当前插件资源目录: ${it.presentableUrl}".substr(0, 60) + "..."
                    contentPluginResourceLabel.toolTipText = "当前插件资源目录: ${it.presentableUrl}"
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

        })

        actionGroup.add(object : AnAction({ "重置" }, Icons.RESET_ICON) {
            override fun actionPerformed(e: AnActionEvent) {
                contentPluginDirLabel.text = "当前无插件目录"
                contentPluginDirLabel.toolTipText = "当前无插件目录"
                contentPluginResourceLabel.text = "当前无插件资源目录"
                contentPluginResourceLabel.toolTipText = "当前无插件资源目录"
                contentPluginPanel.removeAll()
                contentPluginPanel.validate()
                contentPluginPanel.repaint()
                pluginClasspath.set("")
                pluginResourcePath.set("")
                //卸载插件
                pluginInstance.get()?.let {
                    it.closePanel(project)
                    it.closeProject(project)
                    it.unInstall()
                }
                pluginInstance.set(null)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

        })

        actionGroup.add(object : AnAction({ "运行插件" }, Icons.RUN_ICON) {
            override fun actionPerformed(e: AnActionEvent) {
                if (StringUtils.isEmpty(pluginClasspath.get())) {
                    project.chooseDirectory("选择插件编译后的class目录") {
                        pluginClasspath.set(it.presentableUrl)
                        contentPluginDirLabel.text = "当前插件目录: ${it.presentableUrl}".substr(0, 60) + "..."
                        contentPluginDirLabel.toolTipText = "当前插件目录: ${it.presentableUrl}"
                    }
                }
                contentPluginPanel.removeAll()
                try {
                    this.pluginManager()
                        .loadInstanceByDir(pluginClasspath.get(), pluginResourcePath.get()) { plugin, _, error ->
                            if (error != null) {
                                contentPluginPanel.add(
                                    JLabel("插件运行失败,错误信息: $error", JLabel.CENTER),
                                    BorderLayout.CENTER
                                )
                            } else {
                                //老的插件需要卸载
                                pluginInstance.get()?.let {
                                    it.closePanel(project)
                                    it.closeProject(project)
                                    it.unInstall()
                                }
                                pluginInstance.set(plugin!!)
                                plugin.openProject(project)
                                val pluginPanel = plugin.createPanel(project)
                                contentPluginPanel.add(pluginPanel, BorderLayout.CENTER)
                                plugin.showPanel(project)
                            }
                        }
                } catch (e: Throwable) {
                    contentPluginPanel.add(
                        JLabel("插件运行失败,错误信息: ${e.message}", JLabel.CENTER),
                        BorderLayout.CENTER
                    )
                }
                contentPluginPanel.validate()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }

        })
    }

    override fun getPanel(): JComponent {
        return panel
    }
}