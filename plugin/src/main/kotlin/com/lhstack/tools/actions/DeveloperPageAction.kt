package com.lhstack.tools.actions

import cn.hutool.core.util.ZipUtil
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.dependencies.FileDependencyModel
import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.SpeedSearchFilter
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.awt.RelativePoint
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import com.lhstack.tools.dev.DevPluginRegistry
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.*
import com.lhstack.tools.plugins.CefCacheManager
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginType
import com.lhstack.tools.plugins.pluginManager
import org.apache.commons.io.FileUtils
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileFilter
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipOutputStream
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel


fun Project.getModules(): MutableList<Module> {
    return ModuleManager.getInstance(this).modules.filter {
        val modulePropertyManager = ExternalSystemModulePropertyManager.getInstance(it)
        val systemId = modulePropertyManager.getExternalSystemId()
        if (ModuleRootManager.getInstance(it).sourceRoots.size > 0) {
            if (systemId.equalsAnyIgnoreCase("gradle")) {
                it.name.endsWith(".main")
            } else {
                true
            }
        } else {
            false
        }
    }.toMutableList()
}

@SuppressWarnings(value = ["JavaReflectionMemberAccess", "unchecked"])
class DeveloperPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件开发" }, Icons.developerIcon(), windowPanel) {

    private val panel: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true)

    private val contentPanel: JPanel = JPanel(BorderLayout())

    private val pluginInstance = AtomicReference<IPlugin>()

    private val contentPanelInstance = AtomicReference<JComponent>()

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
                project.getModules().apply {
                    if (this.isNotEmpty()) {
                        setItems(this, this[0])
                    } else {
                        setItems(mutableListOf<Module>(), null)
                    }
                }

            }

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun update(item: Module?, presentation: Presentation, popup: Boolean) {
                if (item != null) {
                    if (!popup) {
                        presentation.text = item.name.substr(0, 20) { "$it..." }
                    } else {
                        presentation.text = item.name
                    }
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
        actionGroup.add(object : ToggleAction({ "开启JS插件" }, Icons.jsIcon()) {
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

        actionGroup.add(object : ToggleAction({ "安装开发依赖" }, Icons.libraryIcon()) {

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun isSelected(e: AnActionEvent): Boolean {

                return comboBoxAction.selection?.let { hasInstallLibrary(it) } ?: false
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                comboBoxAction.selection?.let {
                    if (!hasInstallLibrary(it)) {
                        installLibrary(comboBoxAction)
                    } else {
                        unInstallLibrary(comboBoxAction)
                    }
                }
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
                project.getModules().apply {
                    comboBoxAction.setItems(this, this[0])
                    comboBoxAction.update()
                }

            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        actionGroup.add(object : AnAction({ "生成ToolsPlugin.txt" }, AllIcons.Actions.GeneratedFolder) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                if (!developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                comboBoxAction.selection?.let {
                    var sourceRoots = ModuleRootManager.getInstance(it).getSourceRoots(JavaResourceRootType.RESOURCE)
                    if (sourceRoots.isEmpty()) {
                        sourceRoots = ModuleRootManager.getInstance(it).getSourceRoots(false).toList()
                    }
                    if (sourceRoots.isNotEmpty()) {
                        val moduleSourceFile = sourceRoots[0]
                        val toolsPluginFile = moduleSourceFile.findChild("META-INF")?.findChild("ToolsPlugin.txt")
                        if (toolsPluginFile != null) {
                            val result = Messages.showYesNoDialog(
                                "当前已存在ToolsPlugin.txt,是否覆盖",
                                "警告",
                                AllIcons.General.NotificationWarning
                            )
                            if (result == Messages.NO) {
                                return
                            }
                        }
                        val iPluginClass = JavaPsiFacade.getInstance(project)
                            .findClass("com.lhstack.tools.plugins.IPlugin", GlobalSearchScope.allScope(project))
                        if (iPluginClass == null) {
                            project.errorNotify("错误", "先安装开发依赖吧")
                            return
                        }
                        val classes =
                            ClassInheritorsSearch.search(iPluginClass, GlobalSearchScope.moduleScope(it), true)
                        val filterClasses =
                            classes.filter { clazz -> !clazz.isInterface && !clazz.hasModifierProperty("abstract") && !clazz.isEnum }
                        if (filterClasses.size > 1) {
                            val listPopupStep =
                                object : BaseListPopupStep<PsiClass>("实现类", filterClasses) {
                                    override fun isSpeedSearchEnabled(): Boolean {
                                        return true
                                    }

                                    override fun getSpeedSearchFilter(): SpeedSearchFilter<PsiClass> =
                                        SpeedSearchFilter<PsiClass> {
                                            it.qualifiedName
                                        }

                                    override fun onChosen(
                                        selectedValue: PsiClass,
                                        finalChoice: Boolean,
                                    ): PopupStep<*>? {
                                        File(moduleSourceFile.presentableUrl, "META-INF").let { metaInf ->
                                            if (!metaInf.exists()) {
                                                metaInf.mkdirs()
                                            }
                                            File(metaInf, "ToolsPlugin.txt").apply {
                                                writeText(selectedValue.qualifiedName!!)
                                                this.refresh()
                                            }
                                            project.errorNotify("插件开发", "需要重新编译")
                                        }
                                        return super.onChosen(selectedValue, finalChoice)
                                    }

                                    override fun getTextFor(value: PsiClass): String = value.qualifiedName.toString()

                                }
                            val popup =
                                JBPopupFactory.getInstance().createListPopup(listPopupStep, 10)
                            val event = e.inputEvent as MouseEvent
                            popup.show(RelativePoint(event.component, Point(event.point.x + 10, event.point.y + 10)))
                            return
                        } else if (filterClasses.size == 1) {
                            val pluginImpl = filterClasses[0]
                            File(moduleSourceFile.presentableUrl, "META-INF").let { metaInf ->
                                if (!metaInf.exists()) {
                                    metaInf.mkdirs()
                                }
                                File(metaInf, "ToolsPlugin.txt").apply {
                                    writeText(pluginImpl.qualifiedName!!)
                                    this.refresh()
                                }
                            }
                            project.errorNotify("插件开发", "需要重新编译")
                            return
                        } else {
                            project.errorNotify("插件开发", "请先创建com.lhstack.tools.plugins.IPlugin的实现类吧")
                            return
                        }
                    } else {
                        project.errorNotify("插件开发", "请先创建resources目录吧")
                        return
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
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
                        ApplicationManager.getApplication().invokeLater {
                            ModuleRootManager.getInstance(it).contentRoots.forEach { root ->
                                root.refresh(false, true)
                            }
                        }
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })
        actionGroup.add(object : AnAction({ "运行插件" }, AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                run(e, comboBoxAction)
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
                        ApplicationManager.getApplication().invokeLater {
                            ModuleRootManager.getInstance(it).contentRoots.forEach { root ->
                                root.refresh(false, true)
                            }
                        }
                        run(e, comboBoxAction)
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
                                closePanel(project, contentPanelInstance.get())
                            }
                            this
                        }?.catch("项目关闭回调") {
                            ProjectManager.getInstance().openProjects.forEach {
                                it.catch("触发项目关闭回调: ${it.name}") {
                                    closeProject(it)
                                }
                            }
                            this
                        }?.catch("app关闭回调") {
                            appClose()
                            this
                        }?.catch("插件卸载回调") {
                            unInstall()
                        }
                        pluginInstance.set(null)
                        DevPluginRegistry.clear()
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

        actionGroup.add(object : AnAction({ "打包" }, AllIcons.Toolwindows.ToolWindowBuild) {

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {

                project.guessProjectDir()?.let {
                    val fileChooserDescriptor = FileChooserDescriptor(true, true, true, true, false, true)
                    fileChooserDescriptor.setRoots(it)
                    fileChooserDescriptor.title = "请选择构建插件所需要的相关目录和文件"
                    fileChooserDescriptor.isShowFileSystemRoots = false
                    fileChooserDescriptor.isForcedToUseIdeaFileChooser = true
                    val fileChooserDialog =
                        FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, project, null)
                    val virtualFiles = fileChooserDialog.choose(project, *it.children)
                    if (virtualFiles.isNotEmpty()) {
                        ByteArrayOutputStream().use { bo ->
                            ZipOutputStream(bo).use { zo ->
                                val files = virtualFiles.map { file -> File(file.presentableUrl) }.toTypedArray()
                                ZipUtil.zip(
                                    zo,
                                    Charset.forName("UTF-8"),
                                    true,
                                    object : FileFilter {
                                        override fun accept(pathname: File): Boolean {
                                            return true
                                        }
                                    },
                                    *files
                                )
                                val fileSaverDescriptor = FileSaverDescriptor("选择打包结果保存的目录", "保存", "zip")
                                val fileWrapper =
                                    FileChooserFactory.getInstance().createSaveFileDialog(fileSaverDescriptor, project)
                                        .save(null)
                                fileWrapper?.file?.apply {
                                    FileOutputStream(this).use { fo ->
                                        fo.write(bo.toByteArray())
                                        project.refresh {}
                                    }
                                }

                            }
                        }
                    }
                }

            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })

        actionGroup.add(object : AnAction({ "模板" }, AllIcons.Nodes.Template) {

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                val event = e.inputEvent as MouseEvent
                val popup = JBPopupFactory.getInstance()
                    .createListPopup(object : BaseListPopupStep<String>("模板", "嵌套外部网站", "自己开发") {
                        override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                            project.guessProjectDir()?.let {
                                var file = it.findChild("pluginInfo.json")
                                if (file != null) {
                                    val result = Messages.showYesNoDialog(
                                        "当前项目下已存在插件信息,如果点击确认,会覆盖当前已有的部分内容,是否确认",
                                        "警告",
                                        AllIcons.General.Warning
                                    )
                                    if (result == Messages.NO) {
                                        return super.onChosen(selectedValue, finalChoice)
                                    }
                                }
                                when (selectedValue) {
                                    "嵌套外部网站" -> {
                                        DeveloperPageAction::class.java.classLoader.getResourceAsStream("META-INF/template/js-external.zip")
                                            ?.use { stream ->
                                                ZipUtil.unzip(stream, File(it.presentableUrl), Charset.forName("UTF-8"))
                                            }
                                    }

                                    "自己开发" -> {
                                        DeveloperPageAction::class.java.classLoader.getResourceAsStream("META-INF/template/js-inner.zip")
                                            ?.use { stream ->
                                                ZipUtil.unzip(stream, File(it.presentableUrl), Charset.forName("UTF-8"))
                                            }
                                    }

                                    else -> {

                                    }
                                }
                                project.refresh {}
                            }
                            return super.onChosen(selectedValue, finalChoice)
                        }
                    })
                popup.show(RelativePoint(event.component, event.point))
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })

        actionGroup.add(object : AnAction({ "生成类型定义" }, AllIcons.Nodes.Library) {

            override fun update(e: AnActionEvent) {
                super.update(e)
                if (developerState.isJavaPlugin()) {
                    e.presentation.isEnabledAndVisible = false
                    return
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                project.guessProjectDir()?.let { projectDir ->
                    try {
                        // 从资源中读取 jtools-sdk.d.ts
                        val dtsContent = DeveloperPageAction::class.java.classLoader
                            .getResourceAsStream("js/jtools-sdk.d.ts")
                            ?.use { stream ->
                                String(stream.readAllBytes(), StandardCharsets.UTF_8)
                            }

                        if (dtsContent != null) {
                            // 写入到项目目录
                            val targetFile = File(projectDir.presentableUrl, "jtools-sdk.d.ts")
                            FileOutputStream(targetFile).use {
                                it.write(dtsContent.toByteArray(StandardCharsets.UTF_8))
                            }
                            project.refresh {
                                project.notify(
                                    "导出成功",
                                    "类型声明文件已导出到: ${targetFile.absolutePath}",
                                    NotificationType.INFORMATION
                                )
                            }
                        } else {
                            project.errorNotify("导出失败", "无法读取类型声明文件")
                        }
                    } catch (ex: Exception) {
                        project.errorNotify("导出失败", ex.message ?: "未知错误")
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        })
    }

    private fun run(e: AnActionEvent, comboBoxAction: AbstractComboBoxAction<Module>) {

        if (!developerState.isJavaPlugin()) {

            val basePath = project.basePath
            val pluginInfo = File(basePath, "pluginInfo.json")
            if (!pluginInfo.exists()) {
                FileOutputStream(pluginInfo).use {
                    it.write(
                        """
                        {
                            "pluginName":"插件名称",
                            "pluginDesc":"插件描述",
                            "pluginIcon":"插件在插件列表中展示的图标,支持svg,png等,大小为48x48",
                            "pluginTabIcon":"插件tab标签上的图标",
                            "indexPage":"插件入口地址,详情参考模板"
                        }
                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    )
                    project.refresh {
                        LocalFileSystem.getInstance().findFileByIoFile(pluginInfo)?.let { file ->
                            FileEditorManager.getInstance(project).openFile(file, true)
                        }
                    }
                }
                project.errorNotify(
                    "运行JS插件通知",
                    "请先在pluginInfo.json中添加你的配置吧"
                )

                return
            }
            //不是java插件,就是js插件,移除之前的插件
            contentPanel.removeAll()
            pluginInstance.get()?.let { plugin ->
                plugin.catch("关闭插件面板回调") {
                    if (this.pluginType() != PluginType.JAVA_NON_UI) {
                        closePanel(project, contentPanelInstance.get())
                    }
                    this
                }?.catch("项目关闭回调") {
                    ProjectManager.getInstance().openProjects.forEach {
                        it.catch("触发项目关闭回调: ${it.name}") {
                            closeProject(it)
                        }
                    }
                    this
                }?.catch("app关闭回调") {
                    appClose()
                    this
                }?.catch("插件卸载回调") {
                    unInstall()
                }
            }
            this.pluginManager().loadInstanceByDir(mutableListOf(Paths.get(basePath!!)), object : CefCacheManager {
                override fun set(global: Boolean, project: Project, key: String, value: String) {
                    developerState.jsCache[key] = value
                }

                override fun get(global: Boolean, project: Project, key: String): String? {
                    return developerState.jsCache[key]
                }

                override fun getOrDefault(
                    global: Boolean,
                    project: Project,
                    key: String,
                    defaultValue: String
                ): String {
                    return developerState.jsCache[key] ?: defaultValue
                }

                override fun getAll(global: Boolean, project: Project): Map<String, String> {
                    return developerState.jsCache.toMap()
                }

                override fun keys(global: Boolean, project: Project): Set<String> {
                    return developerState.jsCache.keys.toSet()
                }

                override fun exists(global: Boolean, project: Project, key: String): Boolean {
                    return developerState.jsCache.containsKey(key)
                }

                override fun clear(global: Boolean, project: Project) {
                    developerState.jsCache.clear()
                }

                override fun remove(global: Boolean, project: Project, key: String) {
                    developerState.jsCache.remove(key)
                }

                override fun setAll(global: Boolean, project: Project, data: Map<String, String>) {
                    developerState.jsCache.putAll(data)
                }

                override fun size(global: Boolean, project: Project): Int {
                    return developerState.jsCache.size
                }

            }) { plugin, info, err ->
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
                        ProjectManager.getInstance().openProjects.forEach {
                            it.catch("触发项目打开回调:${it.name}") {
                                openProject(it, info!!.logImpl(it)) {
                                    //开发者模式不支持此功能
                                    it.notify(
                                        "插件开发通知",
                                        "开发者模式不支持openThisPage功能",
                                        NotificationType.INFORMATION
                                    )
                                }
                            }
                        }

                        this
                    }?.catch("创建插件面板回调") {
                        if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                            val pluginPanel = plugin.createPanel(project)
                            showPanel(project, pluginPanel)
                            contentPanelInstance.set(pluginPanel)
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
                        info?.let { DevPluginRegistry.set(plugin, it) }
                        comboBoxAction.update()
                    }
                }
            }

            return
        }

        comboBoxAction.selection?.let {
            var sourceRoots = ModuleRootManager.getInstance(it).getSourceRoots(JavaResourceRootType.RESOURCE)
            if (sourceRoots.isEmpty()) {
                sourceRoots = ModuleRootManager.getInstance(it).getSourceRoots(false).toList()
            }
            if (sourceRoots.isNotEmpty()) {
                val moduleSourceFile = sourceRoots[0]
                val toolsPluginFile = moduleSourceFile.findChild("META-INF")?.findChild("ToolsPlugin.txt")
                if (toolsPluginFile == null) {
                    val iPluginClass = JavaPsiFacade.getInstance(project)
                        .findClass("com.lhstack.tools.plugins.IPlugin", GlobalSearchScope.allScope(project))
                    if (iPluginClass == null) {
                        project.errorNotify("插件开发", "先安装开发依赖吧")
                        return
                    }
                    val classes = ClassInheritorsSearch.search(iPluginClass, GlobalSearchScope.moduleScope(it), true)
                    val filterClasses =
                        classes.filter { clazz -> !clazz.isInterface && !clazz.hasModifierProperty("abstract") && !clazz.isEnum }
                    if (filterClasses.isEmpty()) {
                        project.errorNotify("插件开发", "请先创建com.lhstack.tools.plugins.IPlugin的实现类吧")
                        return
                    }
                }
            } else {
                project.errorNotify("插件开发", "请先创建resources目录吧")
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
                                closePanel(project, contentPanelInstance.get())
                            }
                            this
                        }?.catch("项目关闭回调") {
                            ProjectManager.getInstance().openProjects.forEach {
                                it.catch("触发项目关闭回调: ${it.name}") {
                                    closeProject(it)
                                }
                            }
                            this
                        }?.catch("app关闭回调") {
                            appClose()
                            this
                        }?.catch("插件卸载回调") {
                            unInstall()
                        }
                    }
                    val resourcePaths =
                        ModuleRootManager.getInstance(it).getSourceRoots(JavaResourceRootType.RESOURCE)
                            .map { resource -> resource.toNioPath() }
                    val list = mutableListOf(classes.toNioPath()).apply {
                        addAll(resourcePaths)
                        addAll(it.allLibraryPaths())
                    }
                    this.pluginManager().loadInstanceByDir(list, null) { plugin, pluginInfo, err ->
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

                                ProjectManager.getInstance().openProjects.forEach {
                                    it.catch("触发项目打开回调:${it.name}") {
                                        openProject(it, pluginInfo!!.logImpl(it)) {
                                            //开发者模式不支持此功能
                                            it.notify(
                                                "插件开发通知",
                                                "开发者模式不支持openThisPage功能",
                                                NotificationType.INFORMATION
                                            )
                                        }
                                    }
                                }
                                this
                            }?.catch("创建插件面板回调") {
                                if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                                    val pluginPanel = plugin.createPanel(project)
                                    showPanel(project, pluginPanel)
                                    plugin.tabPanelActions(project, pluginPanel)?.let {
                                        if (it.isNotEmpty()) {
                                            val actionToolbar = ActionManager.getInstance()
                                                .createActionToolbar(
                                                    "JTools:Plugin:${pluginInfo?.id}",
                                                    DefaultActionGroup().apply {
                                                        this.addAll(it)
                                                    },
                                                    true
                                                )
                                            actionToolbar.targetComponent = pluginPanel
                                            contentPanel.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                                                this.add(actionToolbar.component)
                                            }, BorderLayout.NORTH)
                                        }
                                    }
                                    contentPanelInstance.set(pluginPanel)
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
                                pluginInfo?.let { DevPluginRegistry.set(plugin, it) }
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

    private fun hasInstallLibrary(module: Module): Boolean {
        val modulePropertyManager = ExternalSystemModulePropertyManager.getInstance(module)
        val systemId = modulePropertyManager.getExternalSystemId()
        if (systemId.equalsAnyIgnoreCase(GradleConstants.SYSTEM_ID.id)) {
            ProjectBuildModel.get(project).getModuleBuildModel(module)?.let {
                for (dependencyModel in it.dependencies().all()) {
                    if (dependencyModel is FileDependencyModel) {
                        if (dependencyModel.file().valueAsString() == Const.JTOOLS_SDK_INSTALL_PATH) {
                            return true
                        }
                    }
                }
            }
            return false
        } else if (systemId.equalsAnyIgnoreCase("maven")) {
            MavenProjectsManager.getInstance(project).findProject(module)?.let {
                PsiManager.getInstance(project).findFile(it.file)?.apply {
                    if (this is XmlFile) {
                        val dependencies = this.rootTag?.findFirstSubTag("dependencies")
                        dependencies?.findSubTags("dependency")?.forEach { dependency ->
                            val groupId = dependency.findFirstSubTag("groupId")?.value?.text
                            val artifactId = dependency.findFirstSubTag("artifactId")?.value?.text
                            if (groupId == Const.JTOOLS_SDK_MAVEN_GROUP_ID && artifactId == Const.JTOOLS_SDK_MAVEN_ARTIFACT_ID) {
                                return true
                            }
                        }
                        return false
                    }
                }
            }
        } else {
            val libraryTablesRegistrar = LibraryTablesRegistrar.getInstance()
            val library = libraryTablesRegistrar.libraryTable.getLibraryByName(Const.JTOOLS_SDK_IDEA_PROJECT_LIBRARY)
            if (library != null) {
                for (orderEntry in ModuleRootManager.getInstance(module).orderEntries) {
                    if (orderEntry is LibraryOrderEntry && orderEntry.libraryName == Const.JTOOLS_SDK_IDEA_PROJECT_LIBRARY) {
                        return true
                    }
                }
            }
            return false
        }
        return false
    }

    private fun unInstallLibrary(moduleComboBox: AbstractComboBoxAction<Module>) {
        DumbService.getInstance(project).runWhenSmart {
            WriteCommandAction.runWriteCommandAction(project) {
                moduleComboBox.selection?.apply {
                    val that = this
                    val modulePropertyManager = ExternalSystemModulePropertyManager.getInstance(this)
                    val systemId = modulePropertyManager.getExternalSystemId()
                    if (systemId.equalsAnyIgnoreCase(GradleConstants.SYSTEM_ID.id)) {
                        ProjectBuildModel.get(project).getModuleBuildModel(this)?.let {
                            for (dependencyModel in it.dependencies().all()) {
                                if (dependencyModel is FileDependencyModel) {
                                    if (dependencyModel.file().valueAsString() == Const.JTOOLS_SDK_INSTALL_PATH) {
                                        it.dependencies().remove(dependencyModel)
                                    }
                                }
                            }
                            it.applyChanges()
                        }
                        ExternalSystemUtil.refreshProject(
                            project.basePath!!,
                            ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                        )
                    } else if (systemId.equalsAnyIgnoreCase("maven")) {
                        MavenProjectsManager.getInstance(project).findProject(this)?.let {
                            PsiManager.getInstance(project).findFile(it.file)?.apply {
                                if (this is XmlFile) {
                                    var forDelete = false
                                    val dependencies = this.rootTag?.findFirstSubTag("dependencies")
                                    dependencies?.findSubTags("dependency")?.forEach { dependency ->
                                        val groupId = dependency.findFirstSubTag("groupId")?.value?.text
                                        val artifactId = dependency.findFirstSubTag("artifactId")?.value?.text
                                        if (groupId == Const.JTOOLS_SDK_MAVEN_GROUP_ID && artifactId == Const.JTOOLS_SDK_MAVEN_ARTIFACT_ID) {
                                            dependency.delete()
                                            forDelete = true
                                        }
                                    }
                                    if (forDelete) {
                                        PsiDocumentManager.getInstance(project).getDocument(this)?.apply {
                                            FileDocumentManager.getInstance().saveDocument(this)
                                        }
                                        MavenProjectsManager.getInstance(project)
                                            .forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                                    }

                                }
                            }
                        }
                    } else {
                        val libraryTablesRegistrar = LibraryTablesRegistrar.getInstance()
                        val library =
                            libraryTablesRegistrar.libraryTable.getLibraryByName(Const.JTOOLS_SDK_IDEA_PROJECT_LIBRARY)
                        if (library != null) {
                            val modifiableModel = libraryTablesRegistrar.libraryTable.modifiableModel
                            modifiableModel.removeLibrary(library)
                            modifiableModel.commit()

                            ModuleRootModificationUtil.updateModel(that) { model ->
                                model.findLibraryOrderEntry(library)?.apply {
                                    model.removeOrderEntry(this)
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    private fun installLibrary(moduleComboBox: AbstractComboBoxAction<Module>) {
        DumbService.getInstance(project).runWhenSmart {
            WriteCommandAction.runWriteCommandAction(project) {
                moduleComboBox.selection?.apply {
                    val that = this
                    File(Const.JTOOLS_SDK_INSTALL_PATH).apply {
                        if (!this.exists()) {
                            //创建父级目录
                            File(this.parent).mkdirs()
                        }
                        DeveloperPageAction::class.java.classLoader.getResourceAsStream("META-INF/sdk.jar")?.use {
                            FileUtils.writeByteArrayToFile(this, it.readAllBytes())
                        }
                    }
                    val modulePropertyManager = ExternalSystemModulePropertyManager.getInstance(this)
                    val systemId = modulePropertyManager.getExternalSystemId()
                    if (systemId.equalsAnyIgnoreCase(GradleConstants.SYSTEM_ID.id)) {
                        ProjectBuildModel.get(project).getModuleBuildModel(this)?.let {
                            if (!it.dependencies().files()
                                    .any { f -> f.file().valueAsString() == Const.JTOOLS_SDK_INSTALL_PATH }
                            ) {
                                it.dependencies().addFile("implementation", Const.JTOOLS_SDK_INSTALL_PATH)
                                it.applyChanges()
                            }
                        }
                        ExternalSystemUtil.refreshProject(
                            project.basePath!!, ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                        )
                    } else if (systemId.equalsAnyIgnoreCase("maven")) {
                        MavenProjectsManager.getInstance(project).findProject(this)?.let {
                            MavenDomUtil.getMavenDomProjectModel(project, it.file)?.let { module ->
                                if (!module.dependencies.dependencies.any { i -> i.groupId.value == Const.JTOOLS_SDK_MAVEN_GROUP_ID && i.artifactId.value == Const.JTOOLS_SDK_MAVEN_ARTIFACT_ID }) {
                                    val dependency = module.dependencies.addDependency()
                                    dependency.scope.value = "system"
                                    dependency.systemPath.stringValue = Const.JTOOLS_SDK_INSTALL_PATH
                                    dependency.version.value = Const.JTOOLS_SDK_MAVEN_VERSION
                                    dependency.groupId.value = Const.JTOOLS_SDK_MAVEN_GROUP_ID
                                    dependency.artifactId.value = Const.JTOOLS_SDK_MAVEN_ARTIFACT_ID
                                    dependency.optional.value = true
                                    PsiManager.getInstance(project).findFile(it.file)?.apply {
                                        PsiDocumentManager.getInstance(project).getDocument(this)?.apply {
                                            FileDocumentManager.getInstance().saveDocument(this)
                                        }
                                    }
                                    MavenProjectsManager.getInstance(project)
                                        .forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                                }
                            }
                        }

                    } else {
                        val libraryTablesRegistrar = LibraryTablesRegistrar.getInstance()
                        var library =
                            libraryTablesRegistrar.libraryTable.getLibraryByName(Const.JTOOLS_SDK_IDEA_PROJECT_LIBRARY)
                        if (library == null) {
                            val libraryModifiableModel = libraryTablesRegistrar.libraryTable.modifiableModel
                            library = libraryModifiableModel.createLibrary(Const.JTOOLS_SDK_IDEA_PROJECT_LIBRARY)
                            val modifiableModel = library.modifiableModel
                            VirtualFileManager.getInstance().findFileByUrl(
                                VirtualFileManager.constructUrl(
                                    "jar",
                                    Const.JTOOLS_SDK_INSTALL_PATH + "!/"
                                )
                            )?.apply {
                                modifiableModel.addRoot(this, OrderRootType.CLASSES)
                            }
                            modifiableModel.commit()
                            libraryModifiableModel.commit()
                        }
                        if (!ModuleRootManager.getInstance(that).orderEntries.filterIsInstance<LibraryOrderEntry>()
                                .any { o -> o.libraryName == Const.JTOOLS_SDK_IDEA_PROJECT_LIBRARY }
                        ) {
                            ModuleRootModificationUtil.addDependency(that, library, DependencyScope.PROVIDED, false)
                        }
                    }

                }
            }
        }
    }
}

@State(name = "dev", storages = [Storage("ToolsPluginState.xml")])
@Service
class DeveloperState : PersistentStateComponent<DeveloperState.State> {

    private var state: State = State()

    class State {
        //插件类型
        var pluginType = "java"

        //js插件缓存
        var jsCache = hashMapOf<String, String>()

        fun isJavaPlugin() = pluginType == "java"
    }

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        this.state = state
    }
}
