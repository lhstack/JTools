package com.lhstack.tools.plugins

import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.io.ZipUtil
import com.intellij.util.lang.UrlClassLoader
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.collections.CollectionUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.jetbrains.annotations.NonNls
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


class PluginClassLoader(var builder: UrlClassLoader.Builder, var files: ArrayList<Path>) {

    var urlClassLoader = builder.get()

    companion object {
        fun newInstance(builder: UrlClassLoader.Builder, files: ArrayList<Path>) = PluginClassLoader(builder, files)
    }

    fun loadPlugin(classname: String): IPlugin {
        return (urlClassLoader.loadClass(classname).getConstructor().newInstance() as IPlugin).apply {
            val support = this.support(Helper.JTOOLS_VERSION, Helper.getIdeInfo())
            if(support.support){
                if (!this.support(Helper.JTOOLS_VERSION)) {
                    throw PluginException(
                        PluginInfo("", "", this.pluginName(), this.pluginVersion(), 0, ""),
                        "插件版本不支持",
                        "插件创建失败,请检查你的插件是否支持当前JTools版本,JTools版本: ${Helper.JTOOLS_VERSION},你的插件: ${this.pluginName()}:${this.pluginVersion()},Ide: ${Helper.getIdeInfo().fullApplicationName}"
                    )
                }
            }else {
                throw PluginException(
                    PluginInfo("", "", this.pluginName(), this.pluginVersion(), 0, ""),
                    support.title?:"插件版本不支持",
                    support.message?:"插件创建失败,请检查你的插件是否支持当前JTools版本,JTools版本: ${Helper.JTOOLS_VERSION},你的插件: ${this.pluginName()}:${this.pluginVersion()},Ide: ${Helper.getIdeInfo().fullApplicationName}"
                )
            }
        }
    }

    fun reset(paths: ArrayList<Path>) {
//        files.clear()
//        files.addAll(paths)
    }

    fun getResourceAsStream(name: String): InputStream? = urlClassLoader.getResourceAsStream(name)

}


/**
 * 插件管理
 */
@Service
class PluginManager {

    var pluginInstances = mutableMapOf<PluginInfo, IPlugin>()

    var classloaders = mutableMapOf<PluginInfo, PluginClassLoader>()

    private val projectStatus = hashSetOf<String>()

    companion object {
        fun getInstance(): PluginManager {
            return service<PluginManager>()
        }
    }


    fun plugins(consumer: (PluginInfo, IPlugin) -> Unit) {
        try {
            pluginInstances.forEach { (k, v) -> consumer.invoke(k, v) }
        } catch (e: Throwable) {
            if (e is PluginException) {
                this.errorNotify(
                    e.title, "插件信息: ${e.pluginInfo},错误信息: ${e.msg}"
                )
            } else if (e.cause is PluginException) {
                val pluginException = e.cause as PluginException
                this.errorNotify(
                    pluginException.title,
                    "插件信息: ${pluginException.pluginInfo},异常信息: ${pluginException.msg}"
                )
            } else {
                this.errorNotify("插件生命周期回调异常", e.fullMsg())
            }
        }
    }

    fun installs(consumer: (PluginInfo, IPlugin, Int, Int) -> Unit) {
        val plugins = this.pluginState().plugins
        val values = plugins.values
        values.sortedBy { o1 -> o1.created }.forEachIndexed { index, v ->
            try {
                if (!pluginInstances.contains(v)) {
                    val pluginPath = v.path
                    if (!java.nio.file.Files.exists(Paths.get(pluginPath))) {
                        //如果插件文件不存在,需要卸载
                        plugins.remove(v.id)
                        this.errorNotify(
                            "插件加载",
                            "插件加载失败,移除插件信息,插件名称:${v.name},插件版本:${v.version},错误信息: 插件jar未找到,请检查你的插件jar是否被删除"
                        )
                    } else {
                        val files = arrayListOf(Paths.get(pluginPath))
                        val classLoader = PluginClassLoader.newInstance(
                            UrlClassLoader.build().files(files)
                                .parent(this::class.java.classLoader)
                                .useCache().allowBootstrapResources(false).allowLock(false),
                            files
                        )
                        val toolsPluginSource = classLoader.getResourceAsStream("META-INF/ToolsPlugin.txt")
                        toolsPluginSource?.use {
                            String(it.readAllBytes(), StandardCharsets.UTF_8).ifNotBlank({ s ->
                                val pluginInstance = classLoader.loadPlugin(s)
                                pluginInstances[v] = pluginInstance
                                classloaders[v] = classLoader
                                //执行安装回调
                                pluginInstance.install()
                                consumer.invoke(v, pluginInstance, index, pluginInstances.size)
                            }) {
                                this.errorNotify(
                                    "插件加载",
                                    "插件加载失败,插件名称:${v.name},插件版本:${v.version},错误信息: META-INF/ToolsPlugin.txt中未找到插件类全限定名"
                                )
                            }
                        }
                        if (toolsPluginSource == null) {
                            val resource =
                                classLoader.getResourceAsStream("pluginInfo.json")
                                    ?: throw RuntimeException("pluginInfo.json cannot null")
                            resource.use {
                                val cefPluginInfo = String(it.readAllBytes(), StandardCharsets.UTF_8).let { s ->
                                    GsonBuilder().create().fromJson(s, CefPluginInfo::class.java)
                                }

                                //加载js插件
                                val pluginInstance =
                                    CefPluginImpl(classLoader, cefPluginInfo, CefPluginCefCacheManager(v))
                                pluginInstances[v] = pluginInstance
                                classloaders[v] = classLoader
                                //执行安装回调
                                pluginInstance.install()
                                consumer.invoke(v, pluginInstance, index, pluginInstances.size)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is PluginException) {
                    this.errorNotify(
                        e.title, "插件信息: ${e.pluginInfo},通知信息: ${e.msg}"
                    )
                } else if (e.cause is PluginException) {
                    val pluginException = e.cause as PluginException
                    this.errorNotify(
                        pluginException.title,
                        "插件信息: ${pluginException.pluginInfo},通知信息: ${pluginException.msg}"
                    )
                } else {
                    this.errorNotify(
                        "插件加载", "插件加载失败,插件名称:${v.name},插件版本:${v.version},错误信息:${e.fullMsg()}"
                    )
                }
            }
        }

    }

    fun loadInstanceByDir(
        paths: MutableList<Path>,
        cefCacheManager: CefCacheManager?,
        consumer: (IPlugin?, PluginInfo?, RuntimeException?) -> Unit,
    ) {
        if (CollectionUtils.isNotEmpty(paths)) {
            try {
                val list = arrayListOf<Path>()
                list.addAll(paths)
                val classLoader = PluginClassLoader.newInstance(
                    UrlClassLoader.build().useCache().files(paths).parent(this::class.java.classLoader).useCache()
                        .allowBootstrapResources(false).allowLock(false),
                    list
                )
                val toolsPluginTxt = classLoader.getResourceAsStream("META-INF/ToolsPlugin.txt")
                toolsPluginTxt?.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8).ifNotBlank({ s ->
                        val pluginInstance = classLoader.loadPlugin(s)
                        val pluginInfo = PluginInfo(
                            UUID.randomUUID().toString(),
                            paths.toString(),
                            pluginInstance.pluginName(),
                            pluginInstance.pluginVersion(),
                            System.currentTimeMillis(),
                            "java"
                        )
                        consumer.invoke(pluginInstance, pluginInfo, null)
                    }) {
                        consumer.invoke(
                            null, null, RuntimeException("META-INF/ToolsPlugin.txt未找到实现IPlugin的插件全类限定名")
                        )
                    }
                }
                if (toolsPluginTxt == null) {
                    val resource =
                        classLoader.getResourceAsStream("pluginInfo.json")
                            ?: throw RuntimeException("pluginInfo.json或者META-INF/ToolsPlugin.txt未找到")
                    resource.use {
                        val cefPluginInfo = String(it.readAllBytes(), StandardCharsets.UTF_8).let { s ->
                            GsonBuilder().create().fromJson(s, CefPluginInfo::class.java)
                        }
                        val pluginInfo = PluginInfo(
                            UUID.randomUUID().toString(),
                            paths.toString(),
                            cefPluginInfo.pluginName,
                            cefPluginInfo.pluginVersion,
                            System.currentTimeMillis(),
                            "js"
                        )
                        val pluginInstance = CefPluginImpl(classLoader, cefPluginInfo, cefCacheManager!!)
//                        pluginInstances[pluginInfo] = pluginInstance
                        consumer.invoke(pluginInstance, pluginInfo, null)
                    }

                }
            } catch (e: Throwable) {
                if (e is PluginException) {
                    consumer.invoke(null, null, e)
                } else if (e.cause is PluginException) {
                    val pluginException = e.cause as PluginException
                    this.errorNotify(
                        pluginException.title,
                        "插件信息: ${pluginException.pluginInfo},异常信息: ${pluginException.msg}"
                    )
                } else {
                    consumer.invoke(
                        null, null, RuntimeException("插件安装出错,插件classpath: ${paths},错误信息: ${e.fullMsg()}")
                    )
                }
            }
        } else {
            consumer.invoke(null, null, RuntimeException("插件安装出错,请先编译插件再运行"))
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

                if (StringUtils.equalsIgnoreCase(file.extension, "jar")) {
                    newPluginFile = File(this.pluginState().pluginBasePath, "${pluginId}.jar").parentMkdirs()
                    if (!newPluginFile.exists()) {
                        FileUtils.copyFile(file, newPluginFile)
                    }
                } else {
                    newPluginFile = File(this.pluginState().pluginBasePath, pluginId).parentMkdirs()
                    if (!newPluginFile.exists()) {
                        ZipUtil.extract(file.toPath(), newPluginFile.toPath()) { _, _ -> true }
                    }
                }
                val list = arrayListOf(newPluginFile.toPath())
                val classLoader = PluginClassLoader.newInstance(
                    UrlClassLoader.build().files(list).parent(this::class.java.classLoader)
                        .useCache().allowBootstrapResources(false).allowLock(false), list
                )
                val toolsPluginTxt = classLoader.getResourceAsStream("META-INF/ToolsPlugin.txt")
                toolsPluginTxt?.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8).ifNotBlank({ s ->
                        val pluginInstance = classLoader.loadPlugin(s)
                        val pluginInfo = PluginInfo(
                            pluginId,
                            newPluginFile.absolutePath,
                            pluginInstance.pluginName(),
                            pluginInstance.pluginVersion(),
                            System.currentTimeMillis(),
                            "java"
                        )
                        pluginInstances[pluginInfo] = pluginInstance
                        classloaders[pluginInfo] = classLoader
                        pluginInstance.install()
                        this.pluginState().plugins[pluginId] = pluginInfo
                        consumer.invoke(pluginInstance, pluginInfo, null)
                    }) {
                        newPluginFile.forceDelete()
                        consumer.invoke(null, null, "META-INF/ToolsPlugin.txt未找到实现IPlugin的插件全类限定名")
                    }
                }
                if (toolsPluginTxt == null) {
                    val resource =
                        classLoader.getResourceAsStream("pluginInfo.json")
                            ?: throw RuntimeException("pluginInfo.json cannot null")
                    resource.use {
                        val cefPluginInfo = String(it.readAllBytes(), StandardCharsets.UTF_8).let { s ->
                            GsonBuilder().create().fromJson(s, CefPluginInfo::class.java)
                        }
                        val pluginInfo = PluginInfo(
                            pluginId,
                            newPluginFile.absolutePath,
                            cefPluginInfo.pluginName,
                            cefPluginInfo.pluginVersion,
                            System.currentTimeMillis(),
                            "js"
                        )
                        val pluginInstance =
                            CefPluginImpl(classLoader, cefPluginInfo, CefPluginCefCacheManager(pluginInfo))
                        pluginInstances[pluginInfo] = pluginInstance
                        classloaders[pluginInfo] = classLoader
                        //执行安装回调
                        pluginInstance.install()
                        this.pluginState().plugins[pluginId] = pluginInfo
                        consumer.invoke(pluginInstance, pluginInfo, null)
                    }

                }
            } catch (e: Throwable) {
                if (e is PluginException) {
                    consumer.invoke(null, null, "插件安装出错,插件名称: ${e.pluginInfo.name},错误信息: ${e.msg}")
                } else if (e.cause is PluginException) {
                    val pluginException = e.cause as PluginException
                    consumer.invoke(
                        null,
                        null,
                        "插件安装出错,插件名称: ${pluginException.pluginInfo.name},错误信息: ${pluginException.msg}"
                    )
                } else {
                    consumer.invoke(null, null, "插件安装出错,插件名称: ${file.name},错误信息: ${e.fullMsg()}")
                }
                newPluginFile?.forceDelete()
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
        classloaders.remove(pluginInfo)
        this.pluginState().plugins.remove(pluginInfo.id)
        try {
            File(pluginInfo.path).forceDelete()
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
                    f.forceDelete()
                }
            }
        }
    }

}

fun Any.pluginManager() = PluginManager.getInstance()