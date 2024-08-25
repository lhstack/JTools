package com.lhstack.tools.actions

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.lhstack.tools.actions.plugin.InstallPluginAction
import com.lhstack.tools.components.HoverAttachPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.const.Keys
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import com.lhstack.tools.plugins.PluginManager
import com.lhstack.tools.plugins.pluginManager
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class PluginPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件管理" }, Icons.PLUGIN_ICON, windowPanel), Disposable, PluginListener {
    private val panel: JComponent
    private val connect: MessageBusConnection
    private val pluginManager: PluginManager
    private val pluginPanel: JComponent

    init {
        pluginManager = this.pluginManager()
        connect = ApplicationManager.getApplication().messageBus.connect()
        connect.subscribe(PluginListener.TOPIC, this)
        Disposer.register(project, this)
        pluginPanel = JPanel(WrapLayout(0, 3, 3))
        pluginManager.plugins { pluginInfo, iPlugin ->
            pluginPanel.add(createPluginBox(pluginInfo, iPlugin))
        }
        val toolWindowPanel = SimpleToolWindowPanel(true, true)
        val actionGroup = DefaultActionGroup()
        actionGroup.add(InstallPluginAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar("ToolsPlugin@Toolbar", actionGroup, true)
        actionToolbar.targetComponent = toolWindowPanel
        toolWindowPanel.toolbar = actionToolbar.component
        toolWindowPanel.setContent(JBScrollPane(pluginPanel))
        panel = toolWindowPanel
    }


    private fun createPluginBox(pluginInfo: PluginInfo, plugin: IPlugin): JComponent {
        val boxPanel = HoverAttachPanel()
        boxPanel.layout = VerticalLayout(5)
        boxPanel.border = JBUI.Borders.empty(15, 20)
        boxPanel.putUserData(Keys.PLUGIN_INFO_KEY, pluginInfo)
        boxPanel.putUserData(Keys.PLUGIN_KEY, plugin)
        boxPanel.add(JLabel(plugin.pluginIcon()))
        boxPanel.add(JLabel(plugin.pluginName(), JLabel.CENTER))
        boxPanel.add(JLabel("版本: ${plugin.pluginVersion()}", JLabel.CENTER))
        boxPanel.add(
            JLabel(
                Instant.ofEpochMilli(pluginInfo.created).atZone(ZoneId.systemDefault()).format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                ), JLabel.CENTER
            )
        )
        boxPanel.toolTipText = plugin.pluginDesc()
        boxPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (e!!.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    boxPanel.setBackground(null as Color?)
                    boxPanel.setCursor(Cursor(0))
                    project.messageBus.syncPublisher(ProjectPluginListener.TOPIC).openPanel(pluginInfo, plugin)
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    val listPopup = JBPopupFactory.getInstance().createActionGroupPopup(
                        pluginInfo.name,
                        createPopupActionGroup(boxPanel, pluginInfo, plugin),
                        DataContext.EMPTY_CONTEXT,
                        JBPopupFactory.ActionSelectionAid.MNEMONICS,
                        true
                    )
                    listPopup.show(RelativePoint(e.component, e.point))
                }
            }
        })
        return boxPanel
    }

    fun createPopupActionGroup(
        boxPanel: HoverAttachPanel, pluginInfo: PluginInfo, plugin: IPlugin
    ): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(object : AnAction({ "卸载插件" }, Icons.UNINSTALL_ICON) {
            override fun actionPerformed(e: AnActionEvent) {
                ApplicationManager.getApplication().messageBus.syncPublisher(PluginListener.TOPIC)
                    .uninstall(plugin, pluginInfo)
                plugin.unInstall()
                pluginManager.uninstsall(pluginInfo)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })
        return group
    }

    override fun getPanel(): JComponent {
        return panel
    }

    override fun dispose() {
        connect.disconnect()
    }

    override fun install(plugin: IPlugin, pluginInfo: PluginInfo) {
        val pluginBox = createPluginBox(pluginInfo, plugin)
        pluginPanel.add(pluginBox)
        pluginPanel.validate()
    }

    override fun uninstall(plugin: IPlugin, pluginInfo: PluginInfo) {
        pluginPanel.components.forEach {
            if (it is HoverAttachPanel) {
                val existPlugInfo = it.getUserData(Keys.PLUGIN_INFO_KEY)
                if (existPlugInfo != null) {
                    if (existPlugInfo.id == pluginInfo.id) {
                        project.messageBus.syncPublisher(ProjectPluginListener.TOPIC).uninstall(plugin, pluginInfo)
                        pluginPanel.remove(it)
                        pluginPanel.validate()
                    }
                }
            }
        }
    }

}