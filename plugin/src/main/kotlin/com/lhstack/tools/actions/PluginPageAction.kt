package com.lhstack.tools.actions

import com.google.common.io.Files
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.CompressionUtil
import com.intellij.util.io.ZipUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.lhstack.tools.actions.plugin.InstallPluginAction
import com.lhstack.tools.components.HoverAttachPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.const.Keys
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.*
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.*
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.apache.tools.zip.ZipOutputStream
import java.awt.Color
import java.awt.Cursor
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class PluginPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件管理" }, { Icons.pluginIcon() }, windowPanel), Disposable, PluginListener {
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
            try {
                pluginPanel.add(createPluginBox(pluginInfo, iPlugin))
            } catch (e: Throwable) {
                throw PluginException(pluginInfo, "创建插件面板失败", e.fullMsg())
            }
        }
        pluginPanel.dropTarget = DropTarget(pluginPanel, object : DropTargetAdapter() {
            override fun drop(dtde: DropTargetDropEvent) {
                dtde.acceptDrop(1)
                //判断拖拽文件是否满足要求
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    val files = dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    if (files.size > 1) {
                        project.errorNotify("插件安装", "拖拽安装目前仅支持单个文件")
                        return
                    }
                    val file = files[0]
                    if (StringUtils.equalsAnyIgnoreCase(file.extension,".jar",".zip")) {
                        project.errorNotify("插件安装", "插件仅支持jar,zip包方式安装")
                        return
                    }
                    this.pluginManager().install(file.absolutePath) { plugin, pluginInfo, error ->
                        if (error != null) {
                            project.errorNotify("插件安装", error)
                        } else {
                            plugin?.let { p ->
                                //安装成功,需要通知所有项目的打开事件
                                ProjectManager.getInstance().openProjects.forEach { openProject ->
                                    try {
                                        p.openProject(openProject) {
                                            if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                                                openProject.messageBus.syncPublisher(ProjectPluginListener.TOPIC)
                                                    .openPanel(pluginInfo!!, plugin)
                                            } else {
                                                openProject.notify(
                                                    "插件点击通知",
                                                    "此插件不是UI插件,不存在面板",
                                                    NotificationType.WARNING
                                                )
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        throw PluginException(pluginInfo!!, "打开项目回调", e.fullMsg())
                                    }
                                }
                                ApplicationManager.getApplication().messageBus.syncPublisher(PluginListener.TOPIC)
                                    .install(p, pluginInfo!!)
                            }
                        }
                    }
                }
                dtde.dropComplete(true)
            }
        })
        val toolWindowPanel = SimpleToolWindowPanel(true, true)
        val actionGroup = DefaultActionGroup()
        actionGroup.add(InstallPluginAction())
        actionGroup.add(object :
            DynamicIconAction({ "帮助" }, { Icons.helpIcon() }) {
            override fun actionPerformed(e: AnActionEvent) {
                Messages.showInfoMessage("点击安装按钮或者将插件拖入插件面板进行安装", "提示")
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })
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
                    plugin.catch("打开插件面板回调") {
                        //判断是否是ui插件,非ui插件不支持此功能
                        if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                            project.messageBus.syncPublisher(ProjectPluginListener.TOPIC).openPanel(pluginInfo, plugin)
                        } else {
                            project.notify("插件点击通知", "此插件不是UI插件,不存在面板", NotificationType.WARNING)
                        }
                    }

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
        boxPanel: HoverAttachPanel, pluginInfo: PluginInfo, plugin: IPlugin,
    ): DefaultActionGroup {
        val group = DefaultActionGroup()
       
        group.add(object:DynamicIconAction({"导出插件"},{Icons.exportIcon()}){
            override fun actionPerformed(e: AnActionEvent) {
                //js插件
                if(StringUtils.equalsAnyIgnoreCase(pluginInfo.type,"js")){
                    project.chooseSaveFile("插件导出",pluginInfo.name,plugin.pluginDesc()?:"","zip"){
                        val filePath = it.presentableUrl
                        File(pluginInfo.path).zip(File(filePath))
                        project.infoNotify("插件导出","导出插件成功")
                    }
                }else {
                    //jar插件
                    project.chooseSaveFile("插件导出",pluginInfo.name,plugin.pluginDesc()?:"","jar"){
                        Files.copy(File(pluginInfo.path),File(it.presentableUrl))
                        project.infoNotify("插件导出","导出插件成功")
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        group.add(object : DynamicIconAction({ "卸载插件" }, { Icons.unInstallIcon() }) {
            override fun actionPerformed(e: AnActionEvent) {
                ApplicationManager.getApplication().messageBus.syncPublisher(PluginListener.TOPIC)
                    .uninstall(plugin, pluginInfo)
                project.catch("卸载插件回调异常,插件信息: $pluginInfo") {
                    plugin.unInstall()
                }

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
        try {
            val pluginBox = createPluginBox(pluginInfo, plugin)
            pluginPanel.add(pluginBox)
            pluginPanel.validate()
        } catch (e: Throwable) {
            throw PluginException(pluginInfo, "创建插件面板失败", e.fullMsg())
        }
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
                        pluginPanel.repaint()
                    }
                }
            }
        }
    }

}