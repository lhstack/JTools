package com.lhstack.tools.actions

import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.components.*
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.*
import com.lhstack.tools.plugins.CefPluginImpl
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginType
import com.lhstack.tools.plugins.pluginManager
import org.jetbrains.jps.model.java.JavaResourceRootType
import java.awt.BorderLayout
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class DeveloperPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件开发调试" }, { Icons.developerIcon() }, windowPanel) {

    private val panel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true)

    private val contentPanel: JPanel = JPanel(BorderLayout())

    private val pluginInstance = AtomicReference<IPlugin>()

    private val compilerManager = CompilerManager.getInstance(project)

    private val developerState = project.service<DeveloperState>().state

    init {
        val actionGroup = DefaultActionGroup()
        initActionGroup(actionGroup)
        val actionToolbar = ActionManager.getInstance().createActionToolbar("DeveloperPage@Toolbar", actionGroup, true)
        actionToolbar.targetComponent = panel
        actionToolbar.component.alignmentX = JComponent.RIGHT_ALIGNMENT
        panel.toolbar = actionToolbar.component
        panel.setContent(contentPanel)
    }

    private fun initActionGroup(actionGroup: DefaultActionGroup) {
        val comboBoxAction = object : AbstractComboBoxAction<Module>() {

            init {
                val modules = ModuleManager.getInstance(project).modules
                setItems(modules.toMutableList(), modules[0])
            }

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun update(item: Module, presentation: Presentation, popup: Boolean) {
                if (!popup) {
                    presentation.text = item.name.substr(0, 20) { "$it..." }
                } else {
                    presentation.text = item.name
                }
            }

            override fun selectionChanged(item: Module): Boolean {
                return selection.name != item.name
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

        }
        actionGroup.add(comboBoxAction)
        actionGroup.add(object : DynamicToggleIconAction({ "开启JS插件" }, { Icons.jsIcon() }) {
            override fun isSelected(e: AnActionEvent): Boolean {
                return !developerState.isJavaPlugin()
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (developerState.isJavaPlugin() && state) {
                    developerState.pluginType = "js"
                } else {
                    developerState.pluginType = "java"
                }
//                actionGroup.update(e)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }

        })
        actionGroup.add(object : AnAction({ "刷新模块" }, AllIcons.Actions.Refresh) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                val modules = ModuleManager.getInstance(project).modules
                comboBoxAction.setItems(modules.toMutableList(), modules[0])
                comboBoxAction.update()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        actionGroup.add(object : AnAction({ "编译模块" }, AllIcons.Actions.Compile) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {

                comboBoxAction.selection?.let {
                    val compileScope =
                        compilerManager.createModulesCompileScope(arrayOf(it), true, true, false)
                    compilerManager.make(compileScope) { _, _, _, _ ->
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })
        actionGroup.add(object : AnAction({ "运行插件" }, AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                run(comboBoxAction)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }

        })

        actionGroup.add(object : AnAction({ "编译并运行" }, AllIcons.Actions.RunAll) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                comboBoxAction.selection?.let {
                    val compileScope =
                        compilerManager.createModulesCompileScope(arrayOf(it), true, true, false)
                    compilerManager.make(compileScope) { abort, errors, _, _ ->
                        if (abort) {
                            return@make
                        }
                        if (errors > 0) {
                            return@make
                        }
                        run(comboBoxAction)
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })

        actionGroup.add(object : ToggleAction({ "停止运行" }) {

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (pluginInstance.get() != null) {
                    e.presentation.icon = Icons.stopHoverIcon()
                } else {
                    e.presentation.icon = Icons.stopIcon()
                }
            }

            override fun isSelected(e: AnActionEvent): Boolean {
                return pluginInstance.get() != null
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (pluginInstance.get() != null) {
                    pluginInstance.get().let { plugin ->
                        plugin.catch("关闭插件面板回调") {
                            if (this.pluginType() != PluginType.JAVA_NON_UI) {
                                closePanel(project)
                            }
                            this
                        }?.catch("项目关闭回调") {
                            closeProject(project)
                            this
                        }?.catch("插件卸载回调") {
                            unInstall()
                            this
                        }?.catch("app关闭回调") {
                            appClose()
                        }
                        pluginInstance.set(null)
                        contentPanel.removeAll()
                        contentPanel.validate()
                        contentPanel.repaint()
                        comboBoxAction.update()
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })
    }

    fun run(comboBoxAction: AbstractComboBoxAction<Module>) {
        comboBoxAction.selection?.let {
            if(!developerState.isJavaPlugin()){

                val basePath = project.basePath

                val pluginInfo = File(basePath, "pluginInfo.json")
                if(!pluginInfo.exists()) {
                    project.errorNotify("运行JS插件通知","项目中不存在pluginInfo.json配置,请检查你的项目是否为标准的JS插件")
                    return
                }
                //不是java插件,就是js插件,移除之前的插件
                contentPanel.removeAll()
                pluginInstance.get()?.let { plugin ->
                    plugin.catch("关闭插件面板回调") {
                        if (this.pluginType() != PluginType.JAVA_NON_UI) {
                            closePanel(project)
                        }
                        this
                    }?.catch("项目关闭回调") {
                        closeProject(project)
                        this
                    }?.catch("插件卸载回调") {
                        unInstall()
                        this
                    }?.catch("app关闭回调") {
                        appClose()
                    }
                }
                this.pluginManager().loadInstanceByDir(mutableListOf(Paths.get(basePath!!))){ plugin, _, err ->
                    if (err != null) {
                        if (err is PluginException) {
                            project.errorNotify(err.title, err.msg)
                        } else {
                            project.errorNotify("插件运行失败", err.toString())
                        }
                    } else {
                        plugin!!.catch("安装插件回调") {
                            install()
                            this
                        }?.catch("打开插件回调") {
                            openProject(project) {
                                //开发者模式不支持此功能
                                project.notify(
                                    "插件开发通知",
                                    "开发者模式不支持openThisPage功能",
                                    NotificationType.INFORMATION
                                )
                            }
                            this
                        }?.catch("创建插件面板回调") {
                            if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                                val pluginPanel = plugin.createPanel(project)
                                showPanel(project)
                                contentPanel.add(pluginPanel, BorderLayout.CENTER)
                                contentPanel.validate()
                                contentPanel.repaint()
                            } else {
                                contentPanel.add(
                                    JLabel("当前插件不处于UI模式,无UI面板", JLabel.CENTER),
                                    BorderLayout.CENTER
                                )
                                contentPanel.validate()
                                contentPanel.repaint()
                            }
                            this
                        }?.catch {
                            pluginInstance.set(plugin)
                            comboBoxAction.update()
                        }
                    }
                }

                return
            }
            val moduleOutputDirectory = CompilerPaths.getModuleOutputDirectory(it, false)
            if (moduleOutputDirectory == null) {
                project.errorNotify("插件开发", "当前项目未编译，或者不存在编译结果，请检查你的项目结构")
                return@let
            }
            moduleOutputDirectory.let { classes ->
                try {
                    contentPanel.removeAll()
                    pluginInstance.get()?.let { plugin ->
                        plugin.catch("关闭插件面板回调") {
                            if (this.pluginType() != PluginType.JAVA_NON_UI) {
                                closePanel(project)
                            }
                            this
                        }?.catch("项目关闭回调") {
                            closeProject(project)
                            this
                        }?.catch("插件卸载回调") {
                            unInstall()
                            this
                        }?.catch("app关闭回调") {
                            appClose()
                        }
                    }
                    val resourcePaths =
                        ModuleRootManager.getInstance(it).getSourceRoots(JavaResourceRootType.RESOURCE)
                            .map { resource -> resource.toNioPath() }
                    val list = mutableListOf(classes.toNioPath()).apply {
                        addAll(resourcePaths)
                        addAll(it.allLibraryPaths())
                    }
                    this.pluginManager().loadInstanceByDir(list) { plugin, _, err ->
                        if (err != null) {
                            if (err is PluginException) {
                                project.errorNotify(err.title, err.msg)
                            } else {
                                project.errorNotify("插件运行失败", err.toString())
                            }
                        } else {
                            plugin!!.catch("安装插件回调") {
                                install()
                                this
                            }?.catch("打开插件回调") {
                                openProject(project) {
                                    //开发者模式不支持此功能
                                    project.notify(
                                        "插件开发通知",
                                        "开发者模式不支持openThisPage功能",
                                        NotificationType.INFORMATION
                                    )
                                }
                                this
                            }?.catch("创建插件面板回调") {
                                if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                                    val pluginPanel = plugin.createPanel(project)
                                    showPanel(project)
                                    contentPanel.add(pluginPanel, BorderLayout.CENTER)
                                    contentPanel.validate()
                                    contentPanel.repaint()
                                } else {
                                    contentPanel.add(
                                        JLabel("当前插件不处于UI模式,无UI面板", JLabel.CENTER),
                                        BorderLayout.CENTER
                                    )
                                    contentPanel.validate()
                                    contentPanel.repaint()
                                }
                                this
                            }?.catch {
                                pluginInstance.set(plugin)
                                comboBoxAction.update()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    project.errorNotify("插件运行失败", e.fullMsg())
                }
            }

        }
    }


    override fun getPanel(): JComponent {
        return panel
    }
}

@State(name = "data", storages = [Storage("ToolsPluginDeveloperState.xml")])
@Service
class DeveloperState : PersistentStateComponent<DeveloperState.State> {

    private var state: State = State()

    class State {
        var pluginType = "java"

        fun isJavaPlugin() = pluginType == "java"
    }

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        this.state = state
    }
}