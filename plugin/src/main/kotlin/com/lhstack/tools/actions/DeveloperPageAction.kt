package com.lhstack.tools.actions

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.dependencies.FileDependencyModel
import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.*
import com.lhstack.tools.plugins.CefCacheManager
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginType
import com.lhstack.tools.plugins.pluginManager
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel


fun Project.getModules(): MutableList<Module> {
    return ModuleManager.getInstance(this).modules.filter {
        val modulePropertyManager = ExternalSystemModulePropertyManager.getInstance(it)
        val systemId = modulePropertyManager.getExternalSystemId()
        if (modulePropertyManager.getExternalModuleType() != null) {
            if (StringUtils.equalsAnyIgnoreCase(systemId, "gradle")) {
                it.name.endsWith(".main")
            } else {
                true
            }
        } else {
            false
        }
    }.toMutableList()
}

class DeveloperPageAction(windowPanel: SimpleToolWindowPanel, private val project: Project) :
    AbstractPageAction({ "插件开发调试" }, Icons.developerIcon(), windowPanel) {

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
                project.getModules().apply {
                    if (this.isNotEmpty()) {
                        setItems(this, this[0])
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
        if (developerState.installSdk.contains(comboBoxAction.selection?.name)) {
            this.installLibrary(comboBoxAction)
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

    private fun run(comboBoxAction: AbstractComboBoxAction<Module>) {

        if (!developerState.isJavaPlugin()) {

            val basePath = project.basePath
            val pluginInfo = File(basePath, "pluginInfo.json")
            if (!pluginInfo.exists()) {
                project.errorNotify(
                    "运行JS插件通知",
                    "项目中不存在pluginInfo.json配置,请检查你的项目是否为标准的JS插件"
                )
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
            this.pluginManager().loadInstanceByDir(mutableListOf(Paths.get(basePath!!)), object : CefCacheManager {
                override fun set(global: Boolean, project: Project, key: String, value: String) {
                    developerState.jsCache[key] = value
                }

                override fun get(global: Boolean, project: Project, key: String): String? {
                    return developerState.jsCache[key]
                }

                override fun getAll(global: Boolean, project: Project): Map<String, String> {
                    return developerState.jsCache
                }

                override fun clear(global: Boolean, project: Project) {
                    developerState.jsCache.clear()
                }

                override fun remove(global: Boolean, project: Project, key: String) {
                    developerState.jsCache.remove(key)
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
                        openProject(project, info!!.logImpl(project)) {
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
                                openProject(
                                    project,
                                    pluginInfo!!.logImpl(project)
                                ) {
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
                                    plugin.tabPanelActions(project)?.let {
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

    private fun hasInstallLibrary(module: Module): Boolean {
        val modulePropertyManager = ExternalSystemModulePropertyManager.getInstance(module)
        val systemId = modulePropertyManager.getExternalSystemId()
        if (StringUtils.equalsAnyIgnoreCase(systemId, GradleConstants.SYSTEM_ID.id)) {
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
        } else if (StringUtils.equalsAnyIgnoreCase(systemId, "maven")) {
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
                    if (StringUtils.equalsAnyIgnoreCase(systemId, GradleConstants.SYSTEM_ID.id)) {
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
                            project, GradleConstants.SYSTEM_ID, project.basePath!!, false,
                            ProgressExecutionMode.IN_BACKGROUND_ASYNC
                        )
                    } else if (StringUtils.equalsAnyIgnoreCase(systemId, "maven")) {
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
                                            .forceUpdateProjects(mutableListOf(it))
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
                    if (StringUtils.equalsAnyIgnoreCase(systemId, GradleConstants.SYSTEM_ID.id)) {
                        ProjectBuildModel.get(project).getModuleBuildModel(this)?.let {
                            if (!it.dependencies().files()
                                    .any { f -> f.file().valueAsString() == Const.JTOOLS_SDK_INSTALL_PATH }
                            ) {
                                it.dependencies().addFile("implementation", Const.JTOOLS_SDK_INSTALL_PATH)
                                it.applyChanges()
                            }
                        }
                        ExternalSystemUtil.refreshProject(
                            project, GradleConstants.SYSTEM_ID, project.basePath!!, false,
                            ProgressExecutionMode.IN_BACKGROUND_ASYNC
                        )
                    } else if (StringUtils.equalsAnyIgnoreCase(systemId, "maven")) {
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
                                        .forceUpdateProjects(mutableListOf(it))
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

    companion object {
        fun instance(project: Project) = project.service<DeveloperState>().state
    }

    class State {
        //插件类型
        var pluginType = "java"

        //安装sdk
        var installSdk = hashSetOf<String>()

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