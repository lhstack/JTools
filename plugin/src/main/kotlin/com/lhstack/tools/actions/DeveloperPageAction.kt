package com.lhstack.tools.actions

import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.allLibraryPaths
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.pluginManager
import org.apache.commons.lang3.StringUtils
import org.jetbrains.jps.model.java.JavaResourceRootType
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JPanel

class DeveloperPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件开发调试" }, Icons.DEVELOPER_ICON, windowPanel) {

    private val panel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true)

    private val contentPanel: JPanel = JPanel(BorderLayout())

    private val pluginInstance = AtomicReference<IPlugin>()

    private val compilerManager = CompilerManager.getInstance(project)

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

            override fun update(item: Module, presentation: Presentation, popup: Boolean) {
                presentation.text = item.name
            }

            override fun selectionChanged(item: Module): Boolean {
                return selection.name != item.name
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }

        }
        actionGroup.add(comboBoxAction)
        actionGroup.add(object : AnAction({ "刷新模块" }, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                val modules = ModuleManager.getInstance(project).modules
                comboBoxAction.setItems(modules.toMutableList(), modules[0])
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })

        actionGroup.add(object : AnAction({ "编译模块" }, AllIcons.Actions.Compile) {
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

            override fun isSelected(e: AnActionEvent): Boolean {
                if(pluginInstance.get() != null){
                    e.presentation.icon = Icons.STOP_HOVER_ICON
                    return true
                }else {
                    e.presentation.icon = Icons.STOP_ICON
                    return false
                }
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
               if(pluginInstance.get() != null){
                   pluginInstance.get().let { plugin ->
                       plugin.closePanel(project)
                       plugin.closeProject(project)
                       plugin.unInstall()
                       pluginInstance.set(null)
                       contentPanel.removeAll()
                       contentPanel.validate()
                       contentPanel.repaint()
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
            val moduleOutputDirectory = CompilerPaths.getModuleOutputDirectory(it, false)
            if (moduleOutputDirectory == null) {
                project.errorNotify("插件开发", "当前项目未编译，或者不存在编译结果，请检查你的项目结构")
                return@let
            }
            moduleOutputDirectory.let { classes ->
                try {
                    contentPanel.removeAll()
                    pluginInstance.get()?.let { plugin ->
                        plugin.closePanel(project)
                        plugin.closeProject(project)
                        plugin.unInstall()
                    }
                    val resourcePaths =
                        ModuleRootManager.getInstance(it).getSourceRoots(JavaResourceRootType.RESOURCE)
                            .map { resource -> resource.toNioPath() }
                    val list = mutableListOf(classes.toNioPath()).apply {
                        addAll(resourcePaths)
                        addAll(it.allLibraryPaths())
                    }
                    this.pluginManager().loadInstanceByDir(list) { plugin, _, errorText ->
                        if (StringUtils.isNoneBlank(errorText)) {
                            errorText?.let { text -> project.errorNotify("插件运行失败", text) }
                        } else {
                            plugin!!.install()
                            pluginInstance.set(plugin)
                            plugin.openProject(project)
                            val pluginPanel = plugin.createPanel(project)
                            contentPanel.add(pluginPanel, BorderLayout.CENTER)
                            contentPanel.validate()
                            contentPanel.repaint()
                            plugin.showPanel(project)
                        }
                    }
                } catch (e: Throwable) {
                    e.message?.let { text -> project.errorNotify("插件运行失败", text) }
                }
            }

        }
    }


    override fun getPanel(): JComponent {
        return panel
    }
}