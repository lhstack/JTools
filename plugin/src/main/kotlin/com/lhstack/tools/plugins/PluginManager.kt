package com.lhstack.tools.plugins

import ai.grazie.utils.mpp.UUID
import com.google.common.io.Files
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.lang.UrlClassLoader
import com.lhstack.tools.ext.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.collections.CollectionUtils
import org.jetbrains.annotations.NonNls
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 插件管理
 */
@Service
class PluginManager {

    private val pluginInstances = mutableMapOf<PluginInfo, IPlugin>()

    private val projectStatus = hashSetOf<String>()

    companion object {
        fun getInstance(): PluginManager {
            return service<PluginManager>()
        }
    }


    private class PluginClassLoader(builder: Builder) : UrlClassLoader(
        builder,
        registerAsParallelCapable()
    ) {
        companion object {
            fun newInstance(builder: Builder) = PluginClassLoader(builder)
        }
    }

    fun plugins(consumer: (PluginInfo, IPlugin) -> Unit) {
        pluginInstances.forEach { (k, v) -> consumer.invoke(k, v) }
    }

    fun installs(consumer: (PluginInfo, IPlugin, Int, Int) -> Unit) {
        val plugins = this.pluginState().plugins
        val values = plugins.values
        values.sortedBy { o1 -> o1.created }.forEachIndexed { index, v ->
            try {
                if (!pluginInstances.contains(v)) {
                    val pluginPath = v.path
                    val classLoader = PluginClassLoader.newInstance(
                        UrlClassLoader.build()
                            .files(listOf(Paths.get(pluginPath)))
                            .parent(this::class.java.classLoader).useCache().allowBootstrapResources(false)
                            .allowLock(false)
                    )
                    classLoader.getResourceAsStream("META-INF/ToolsPlugin.txt")?.use {
                        String(it.readAllBytes(), StandardCharsets.UTF_8).ifNotBlank({ s ->
                            val pluginInstance = classLoader.loadClass(s).getConstructor().newInstance() as IPlugin
                            pluginInstances[v] = pluginInstance
                            //执行安装回调
                            pluginInstance.install()
                            consumer.invoke(v, pluginInstance, index, pluginInstances.size)
                        }) {
                            this.errorNotify(
                                "插件加载",
                                "插件加载失败,插件名称:${v.name},插件版本:${v.version},错误信息: META-INF/ToolsPlugin.txt未找到实现IPlugin的插件全类限定名"
                            )
                        }
                    }

                }
            } catch (e: Throwable) {
                e.message?.let {
                    this.errorNotify(
                        "插件加载",
                        "插件加载失败,插件名称:${v.name},插件版本:${v.version},错误信息:${it}"
                    )
                }
            }
        }

    }

    fun loadInstanceByDir(paths: MutableList<Path>, consumer: (IPlugin?, PluginInfo?, String?) -> Unit) {
        if (CollectionUtils.isNotEmpty(paths)) {
            try {
                val classLoader = PluginClassLoader.newInstance(
                    UrlClassLoader.build()
                        .files(paths)
                        .parent(this::class.java.classLoader).useCache().allowBootstrapResources(false)
                        .allowLock(false)
                )
                val toolsPluginTxt = classLoader.getResourceAsStream("META-INF/ToolsPlugin.txt")
                toolsPluginTxt?.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8).ifNotBlank({ s ->
                        val pluginInstance = classLoader.loadClass(s).getConstructor().newInstance() as IPlugin
                        val pluginInfo = PluginInfo(
                            UUID.random().toString(),
                            paths.toString(),
                            pluginInstance.pluginName(),
                            pluginInstance.pluginVersion(),
                            System.currentTimeMillis()
                        )
                        pluginInstance.install()
                        consumer.invoke(pluginInstance, pluginInfo, null)
                    }) {
                        consumer.invoke(null, null, "META-INF/ToolsPlugin.txt未找到实现IPlugin的插件全类限定名")
                    }
                }
                if (toolsPluginTxt == null) {
                    consumer.invoke(null, null, "META-INF/ToolsPlugin.txt文件未找到")
                }
            } catch (e: Throwable) {
                consumer.invoke(null, null, "插件安装出错,插件名称: ${paths},错误信息: ${e.message}")
            }
        } else {
            consumer.invoke(null, null, "插件安装出错,请先编译插件再运行")
        }
    }

    fun install(pluginPath: String, consumer: (IPlugin?, PluginInfo?, String?) -> Unit) {
        val file = File(pluginPath)
        if (file.exists() && file.isFile) {
            var newPluginFile: File? = null
            try {
                val pluginId = DigestUtils.md5Hex(file.readBytes())
                if (this.pluginState().plugins.containsKey(pluginId)) {
                    consumer.invoke(null, null, "插件已存在,请不要重复安装")
                    return
                }
                newPluginFile =
                    File(this.pluginState().pluginBasePath, "${pluginId}.${file.extension}").parentMkdirs()
                Files.copy(file, newPluginFile)
                val classLoader = PluginClassLoader.newInstance(
                    UrlClassLoader.build()
                        .files(listOf(newPluginFile.toPath()))
                        .parent(this::class.java.classLoader).useCache().allowBootstrapResources(false)
                        .allowLock(false)
                )
                val toolsPluginTxt = classLoader.getResourceAsStream("META-INF/ToolsPlugin.txt")
                toolsPluginTxt?.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8).ifNotBlank({ s ->
                        val pluginInstance = classLoader.loadClass(s).getConstructor().newInstance() as IPlugin
                        val pluginInfo = PluginInfo(
                            pluginId,
                            newPluginFile.absolutePath,
                            pluginInstance.pluginName(),
                            pluginInstance.pluginVersion(),
                            System.currentTimeMillis()
                        )
                        pluginInstances[pluginInfo] = pluginInstance
                        pluginInstance.install()
                        consumer.invoke(pluginInstance, pluginInfo, null)
                        this.pluginState().plugins[pluginId] = pluginInfo
                    }) {
                        newPluginFile.forceDelete()
                        consumer.invoke(null, null, "META-INF/ToolsPlugin.txt未找到实现IPlugin的插件全类限定名")
                    }
                }
                if (toolsPluginTxt == null) {
                    consumer.invoke(null, null, "META-INF/ToolsPlugin.txt文件未找到")
                    newPluginFile.forceDelete()
                }
            } catch (e: Throwable) {
                newPluginFile?.forceDelete()
                consumer.invoke(null, null, "插件安装出错,插件名称: ${file.name},错误信息: ${e.message}")
            }
        } else {
            consumer.invoke(null, null, "插件路径错误")
        }
    }

    /**
     * 卸载插件
     */
    fun uninstsall(pluginInfo: PluginInfo) {
        pluginInstances.remove(pluginInfo)
        this.pluginState().plugins.remove(pluginInfo.id)
        try {
            java.nio.file.Files.delete(File(pluginInfo.path).toPath())
        } catch (e: Throwable) {
            e.message?.let {
                this.errorNotify(
                    "插件文件删除失败,也许插件被其他进程占用了,或插件本身占用了,请检查你的插件是否存在有被引用的情况",
                    it
                )
            }
            return
        }
        this.infoNotify("插件卸载", "插件卸载完成")
    }

    /**
     * 插件移除逻辑
     */
    fun remove(projectId: @NonNls String, function: () -> Unit) {
        if (projectStatus.contains(projectId)) {
            function.invoke()
            projectStatus.remove(projectId)
        }
    }

    fun add(projectId: String) {
        projectStatus.add(projectId)
    }

    /**
     * 清除卸载残留
     */
    fun clearUnloadingResidue() {
        val pluginPaths = this.pluginState().plugins.values.map { it.path }.toSet()
        val pluginBasePath = this.pluginState().pluginBasePath
        val file = File(pluginBasePath)
        if (file.exists() && file.isDirectory) {
            val listFiles = file.listFiles()
            listFiles?.forEach { f ->
                if (!pluginPaths.contains(f.absolutePath)) {
                    f.delete()
                }
            }
        }
    }

}

fun Any.pluginManager() = PluginManager.getInstance()