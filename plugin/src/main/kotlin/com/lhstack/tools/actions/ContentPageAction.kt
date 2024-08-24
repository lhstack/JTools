package com.lhstack.tools.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.tabs.JBEditorTabsBase
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.messages.MessageBusConnection
import com.lhstack.tools.components.EmptyPanel
import com.lhstack.tools.components.PluginTabPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class ContentPageAction(
    windowPanel: SimpleToolWindowPanel,
    private val project: Project,
    private val goToPage: (String) -> Unit
) :
    AbstractPageAction({ "插件面板" }, Icons.TOOL_ICON, windowPanel), Disposable, ProjectPluginListener {

    private val tabsPanel: JBEditorTabsBase

    private val contentPanel: JPanel

    private val cardLayout = CardLayout()

    private val messageBusConnection: MessageBusConnection

    private val cardView = "view"

    private val cardEmpty = "empty"

    init {
        contentPanel = JPanel(cardLayout)
        tabsPanel = JBTabsFactory.createEditorTabs(project, this)
        tabsPanel.presentation.setTabDraggingEnabled(true)
        tabsPanel.addListener(object : TabsListener {
            override fun tabRemoved(tabToRemove: TabInfo) {
                if (tabsPanel.tabCount == 0) {
                    cardLayout.show(contentPanel, cardEmpty)
                }
            }
        })
        contentPanel.add(tabsPanel.component, cardView)
        contentPanel.add(EmptyPanel(createAddButton(), "没有内容,请在插件列表中打开一个插件吧"), cardEmpty)
        cardLayout.show(contentPanel, cardEmpty)
        messageBusConnection = project.messageBus.connect()
        messageBusConnection.subscribe(ProjectPluginListener.TOPIC, this)
        Disposer.register(project, this)
    }

    private fun createAddButton(): JButton {
        val button = JButton()
        button.icon = Icons.ADD_ICON
        button.addActionListener {
            goToPage.invoke("plugin")
        }
        return button
    }

    override fun getPanel(): JComponent {
        return contentPanel
    }

    override fun dispose() {
        messageBusConnection.disconnect()
    }

    override fun uninstall(plugin: IPlugin, pluginInfo: PluginInfo) {
        this.tabsPanel.tabs.forEach {
            if (it.component is PluginTabPanel) {
                val pluginTabPanel = it.component as PluginTabPanel
                if (pluginTabPanel.pluginInfo.id == pluginInfo.id) {
                    this.tabsPanel.removeTab(it)
                }
            }
        }
    }

    override fun openPanel(pluginInfo: PluginInfo, plugin: IPlugin) {
        this.tabsPanel.tabs.forEach {
            if (it.component is PluginTabPanel) {
                val pluginTabPanel = it.component as PluginTabPanel
                if (pluginTabPanel.pluginInfo.id == pluginInfo.id) {
                    tabsPanel.select(it, true)
                    goToPage()
                    return
                }
            }
        }
        try {
            val pluginPanel = plugin.createPanel(project)
            val pluginTabPanel = PluginTabPanel(pluginInfo, plugin)
            pluginTabPanel.layout = BorderLayout()
            pluginTabPanel.add(pluginPanel, BorderLayout.CENTER)
            val tabInfo = TabInfo(pluginTabPanel)
            tabInfo.setIcon(plugin.pluginTabIcon())
            tabInfo.setText(pluginInfo.name)
            tabInfo.setTooltipText(plugin.pluginDesc())
            tabInfo.setTabLabelActions(DefaultActionGroup(object : AnAction({ "关闭" }, AllIcons.Actions.Close) {

                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.icon = AllIcons.Actions.Close
                    e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
                }

                override fun actionPerformed(e: AnActionEvent) {
                    tabsPanel.removeTab(tabInfo)
                    plugin.closePanel(project)
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

            }), "tabActionGroup")
            cardLayout.show(contentPanel, cardView)
            tabsPanel.addTab(tabInfo)
            tabsPanel.select(tabInfo, true)
            plugin.showPanel(project)
            goToPage()
        } catch (e: Throwable) {
            e.message?.let { project.errorNotify("插件打开失败", it) }
        }
    }


}