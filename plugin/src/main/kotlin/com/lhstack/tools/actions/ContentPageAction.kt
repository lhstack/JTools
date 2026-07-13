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
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.messages.MessageBusConnection
import com.lhstack.tools.components.EmptyPanel
import com.lhstack.tools.components.PluginTabPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.catch
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import com.lhstack.tools.ui.layout.SplitablePageContainer
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.min


class ContentPageAction(
    windowPanel: SimpleToolWindowPanel,
    private val project: Project,
    private val goToPage: (String) -> Unit,
) :
    AbstractPageAction({ "插件面板" }, Icons.toolIcon(), windowPanel), Disposable, ProjectPluginListener {

    private val rootContainer: SplitablePageContainer
    private val contentPanel: JPanel
    private val cardLayout = CardLayout()
    private val messageBusConnection: MessageBusConnection
    private val cardView = "view"
    private val cardEmpty = "empty"

    private val goToPluginButton: JButton = object : JButton() {
        override fun contains(x: Int, y: Int): Boolean {
            val width = width
            val height = height
            val radius = (min(width.toDouble(), height.toDouble()) / 2).toInt()
            val centerX = width / 2
            val centerY = height / 2
            val dx = x - centerX
            val dy = y - centerY
            return (dx * dx + dy * dy) <= radius * radius
        }
    }.apply {
        this.icon = Icons.addIcon()
        this.setContentAreaFilled(false)
        this.setBorderPainted(false)
        this.setFocusPainted(false)
        this.setOpaque(false)
        this.preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
        this.addMouseListener(object : MouseAdapter() {
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
        rootContainer = SplitablePageContainer(project, this)

        contentPanel.add(rootContainer, cardView)
        contentPanel.add(EmptyPanel(goToPluginButton, "没有内容,请在插件列表中打开一个插件吧"), cardEmpty)

        // Initial state check
        checkEmptyState()

        messageBusConnection = project.messageBus.connect()
        messageBusConnection.subscribe(ProjectPluginListener.TOPIC, this)
        Disposer.register(project, this)
    }

    private fun checkEmptyState() {
        if (rootContainer.hasContent) {
            cardLayout.show(contentPanel, cardView)
        } else {
            cardLayout.show(contentPanel, cardEmpty)
        }
    }

    override fun getPanel(): JComponent {
        return contentPanel
    }

    override fun dispose() {
        messageBusConnection.disconnect()
        // rootContainer disposed by Disposer.register(project, this) -> Disposer.register(this, rootContainer) if child?
        // No, I registered 'this' (ContentPageAction) as parent of RootContainer.
        // ContentPageAction implements Disposable.
        // So Disposer.dispose(this) will dispose children.
        // And Disposer.register(project, this) ensures it's disposed on project close.
    }

    override fun closePanel(plugin: IPlugin, pluginInfo: PluginInfo) {
        rootContainer.closePluginTabs(pluginInfo)
        checkEmptyState()
    }

    override fun openPanel(pluginInfo: PluginInfo, plugin: IPlugin) {
        if (!plugin.supportMultiOpens()) {
            if (rootContainer.selectPluginTab(pluginInfo)) {
                goToPage()
                return
            }
        }

        plugin.catch("创建插件面板") {
            val pluginPanel = plugin.createPanel(project)
            val pluginTabPanel =
                PluginTabPanel(
                    pluginInfo,
                    plugin,
                    pluginPanel,
                    null,
                    UUID.randomUUID().toString()
                ) // tabsPanel set later
            pluginTabPanel.layout = BorderLayout()
            pluginTabPanel.add(pluginPanel, BorderLayout.CENTER)
            val tabInfo = TabInfo(pluginTabPanel)
            tabInfo.setIcon(plugin.pluginTabIcon())
            tabInfo.setText(pluginInfo.name)
            tabInfo.setTooltipText(plugin.pluginDesc())

            // Tab Label Actions (Close)
            tabInfo.setTabLabelActions(DefaultActionGroup(object : AnAction({ "关闭" }, AllIcons.Actions.Close) {
                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.icon = AllIcons.Actions.Close
                    e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
                }

                override fun actionPerformed(e: AnActionEvent) {
                    rootContainer.removeTab(tabInfo)
                    plugin.catch("插件面板关闭回调") { plugin.closePanel(project, pluginPanel) }
                    checkEmptyState()
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

            }), "tabActionGroup")

            Optional.ofNullable(plugin.tabPanelActions(project, pluginPanel)).filter { it.isNotEmpty() }.ifPresent {
                tabInfo.setTabPaneActions(DefaultActionGroup(it))
            }

            cardLayout.show(contentPanel, cardView)
            rootContainer.addTab(tabInfo)

            plugin.catch("插件面板显示回调") { plugin.showPanel(project, pluginPanel) }
            goToPage()
        }
    }

    // Helper access to IPlugin methods if not visible directly
    private fun IPlugin.createPanel(project: Project): JComponent = this.createPanel(project)
    private fun IPlugin.showPanel(project: Project, panel: JComponent) = this.showPanel(project, panel)
    private fun IPlugin.closePanel(project: Project, panel: JComponent) = this.closePanel(project, panel)
}