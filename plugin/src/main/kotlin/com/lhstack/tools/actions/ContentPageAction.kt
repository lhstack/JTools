package com.lhstack.tools.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.tabs.JBEditorTabsBase
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.messages.MessageBusConnection
import com.lhstack.tools.components.EmptyPanel
import com.lhstack.tools.components.FloatingDialog
import com.lhstack.tools.components.PluginTabPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.catch
import com.lhstack.tools.ext.openThisWindow
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.min


class ContentPageAction(
    windowPanel: SimpleToolWindowPanel,
    private val project: Project,
    private val goToPage: (String) -> Unit
) :
    AbstractPageAction({ "插件面板" }, Icons.toolIcon(), windowPanel), Disposable, ProjectPluginListener {

    private val tabsPanel: JBEditorTabsBase

    private val contentPanel: JPanel

    private val cardLayout = CardLayout()

    private val messageBusConnection: MessageBusConnection

    private val cardView = "view"

    private val cardEmpty = "empty"

    private val goToPluginButton: JButton = object:JButton(){
        override fun contains(x: Int, y: Int): Boolean {
            val width = width
            val height = height
            // 计算圆心坐标和半径
            val radius = (min(width.toDouble(), height.toDouble()) / 2).toInt()
            val centerX = width / 2
            val centerY = height / 2
            // 计算点 (x, y) 到圆心的距离
            val dx = x - centerX
            val dy = y - centerY
            // 如果点在圆内，返回 true；否则返回 false
            return (dx * dx + dy * dy) <= radius * radius
        }
    }.apply {
        this.icon = Icons.addIcon()
        this.setContentAreaFilled(false);   // 禁用按钮的背景填充
        this.setBorderPainted(false);       // 去掉边框
        this.setFocusPainted(false);        // 去掉焦点框
        this.setOpaque(false);
        this.preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
        this.addMouseListener(object:MouseAdapter(){
            override fun mouseEntered(e: MouseEvent?) {
                setCursor(Cursor(Cursor.HAND_CURSOR))
                icon = Icons.addHoverIcon()
            }

            override fun mouseExited(e: MouseEvent?) {
                icon = Icons.addIcon()
                setCursor(Cursor(Cursor.DEFAULT_CURSOR))
            }
        })
        this.addActionListener {
            icon = Icons.addIcon()
            goToPage.invoke("plugin")
        }
    }

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
        createTabsPopup()
        contentPanel.add(tabsPanel.component, cardView)
        contentPanel.add(EmptyPanel(goToPluginButton, "没有内容,请在插件列表中打开一个插件吧"), cardEmpty)
        cardLayout.show(contentPanel, cardEmpty)
        messageBusConnection = project.messageBus.connect()
        messageBusConnection.subscribe(ProjectPluginListener.TOPIC, this)
        Disposer.register(project, this)
    }

    private fun createTabsPopup() {
        val tabsPopupGroup = DefaultActionGroup()
        tabsPopupGroup.add(object : AnAction({ "关闭所有标签" }, Icons.closeAllIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                tabsPanel.tabs.forEach { tab ->
                    if (tab.component is PluginTabPanel) {
                        (tab.component as PluginTabPanel).plugin.catch("插件面板关闭回调") { closePanel(project) }
                        tabsPanel.removeTab(tab)
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object:AnAction({"关闭其他标签"},Icons.closeOtherIcon()){
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if(component is TabLabel){
                    val tabInfo = component.info
                    tabsPanel.tabs.forEach { tab ->
                        if (tab.component is PluginTabPanel) {
                            if(tab.component != tabInfo.component){
                                (tab.component as PluginTabPanel).plugin.catch("插件面板关闭回调") { closePanel(project) }
                                tabsPanel.removeTab(tab)
                            }
                        }
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object : AnAction({ "在新窗口中打开" }, Icons.newTabIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    tabInfo.isHidden = true
                    val dialog: FloatingDialog = if (tabInfo.component is PluginTabPanel) {
                        FloatingDialog(
                            project,
                            (tabInfo.component as PluginTabPanel).pluginInfo.name,
                            tabInfo.component
                        )
                    } else {
                        FloatingDialog(project, "新窗口", tabInfo.component)
                    }
                    //关闭回调
                    Disposer.register(dialog.disposable) {
                        tabInfo.isHidden = false
                    }
                    dialog.show()
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })
        tabsPanel.setPopupGroup(tabsPopupGroup, "ContentPage@Tabs", true)
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
                    plugin.catch("插件面板关闭回调") {
                        closePanel(project)
                    }
                }
            }
        }
    }

    override fun openPanel(pluginInfo: PluginInfo, plugin: IPlugin) {
        //需要打开Tools面板
        project.openThisWindow()
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
        plugin.catch("创建插件面板") {
            val pluginPanel = createPanel(project)
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
                    plugin.catch("插件面板关闭回调") { closePanel(project) }
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

            }), "tabActionGroup")
            cardLayout.show(contentPanel, cardView)
            tabsPanel.addTab(tabInfo)
            tabsPanel.select(tabInfo, true)
            plugin.catch("插件面板显示回调") { showPanel(project) }
            goToPage()
        }
    }


}