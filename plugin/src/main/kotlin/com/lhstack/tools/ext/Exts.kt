package com.lhstack.tools.ext

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.containers.stream
import com.lhstack.tools.ToolsMainWindowFactory
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import javax.swing.Icon


fun String.ifNotBlank(consumer: (String) -> Unit, empty: () -> Unit) {
    if (this.isNotBlank()) {
        consumer(StringUtils.trim(this))
    } else {
        empty.invoke()
    }
}

fun String.ifNotBlank(consumer: (String) -> Unit) {
    if (this.isNotBlank()) {
        consumer(StringUtils.trim(this))
    }
}


fun <T> T.infoNotify(title: String, msg: String) {
    this.notify(title, msg, NotificationType.INFORMATION)
}

fun <T> T.errorNotify(title: String, msg: String) {
    this.notify(title, msg, NotificationType.ERROR)
}

fun <T> T.notify(title: String, msg: String, notificationType: NotificationType) {
    if (this is Project) {
        Notifications.Bus.notify(
            Notification("ToolsNotification", title, msg, notificationType).setIcon(
                Icons.notificationIcon()
            ), this
        )
    } else {
        Notifications.Bus.notify(
            Notification("ToolsNotification", title, msg, notificationType).setIcon(
                Icons.notificationIcon()
            )
        )
    }
}

fun Any.findIcon(iconPath: String): Icon {
    return IconLoader.findIcon(iconPath, ToolsMainWindowFactory::class.java)!!
}


fun String.substr(start: Int, end: Int): String {
    if (this.length < end) {
        return this
    }
    return this.substring(start, end)
}


fun String.substr(start: Int, end: Int, apply: (String) -> String): String {
    if (this.length < end) {
        return this
    }
    return apply(this.substring(start, end))
}

fun Project.chooseJarFile(title: String, consumer: (VirtualFile) -> Unit) {
    val fileChooserDescriptor = FileChooserDescriptor(false, true, true, true, false, false)
    fileChooserDescriptor.title = title
    FileChooser.chooseFile(fileChooserDescriptor, this, null)
        ?.let { consumer(it) }
}

fun Project.chooseDirectory(title: String, consumer: (VirtualFile) -> Unit) {
    val fileChooserDescriptor = FileChooserDescriptor(false, true, false, false, false, false)
    fileChooserDescriptor.title = title
    FileChooser.chooseFile(fileChooserDescriptor, this, null)
        ?.let(consumer)
}

fun Project.chooseDirectory(title: String, toSelect: VirtualFile?, consumer: (VirtualFile) -> Unit) {
    val fileChooserDescriptor = FileChooserDescriptor(false, true, false, false, false, false)
    fileChooserDescriptor.title = title
    FileChooser.chooseFile(fileChooserDescriptor, this, toSelect)
        ?.let(consumer)
}

fun Project.chooseSaveFile(
    title: String,
    filename: String,
    description: String,
    extension: String,
    consumer: (VirtualFile) -> Unit
) {
    val fileSaverDescriptor = FileSaverDescriptor(title, description, extension)
    val saveFileDialog = FileChooserFactory.getInstance().createSaveFileDialog(fileSaverDescriptor, this)
    saveFileDialog.let { save ->
        save.save(filename)?.let {
            it.getVirtualFile(true)?.let(consumer)
        }
    }
}

fun Module.allLibraryPaths(): List<Path> {
    val result = ArrayList<Path>()
    val moduleRootManager = ModuleRootManager.getInstance(this)
    for (orderEntry in moduleRootManager.orderEntries) {
        if (orderEntry is ModuleOrderEntry) {
            if (orderEntry.scope == DependencyScope.RUNTIME || orderEntry.scope == DependencyScope.COMPILE) {
                orderEntry.module?.let {
                    result.addAll(it.allLibraryPaths())
                }
            }
        } else if (orderEntry is LibraryOrderEntry) {
            if (orderEntry.scope == DependencyScope.RUNTIME || orderEntry.scope == DependencyScope.COMPILE) {
                orderEntry.library?.let { pack ->
                    val paths = pack.getFiles(OrderRootType.CLASSES).stream()
                        .collect(
                            Collectors.toMap(
                                { it.name },
                                { File(it.presentableUrl).toPath() }) { _, o2 -> o2 })
                    result.addAll(paths.values)
                }
            }
        }
    }
    return result
}

fun File.parentMkdirs(): File {
    val parentFile = this.parentFile
    if (parentFile != null && !parentFile.exists()) {
        parentFile.mkdirs()
    }
    return this
}

fun File.forceDelete() {
    if (this.isDirectory) {
        FileUtils.deleteDirectory(this)
    } else {
        Files.delete(this.toPath())
    }
}

fun Project.openThisWindow() {
    val windowManager = ToolWindowManager.getInstance(this)
    val toolWindow = windowManager.getToolWindow(Const.TOOLS_WINDOW_ID)
    toolWindow?.show()
}

inline fun <T, R> T.catch(block: T.() -> R): R? {
    return try {
        block()
    } catch (e: Throwable) {
        this.errorNotify("执行异常", e.fullMsg())
        null
    }
}

inline fun <T, R> T.catch(title: String, block: T.() -> R): R? {
    return try {
        block()
    } catch (e: Throwable) {
        this.errorNotify(title, e.fullMsg())
        null
    }
}

fun Throwable.fullMsg(): String {
    return this.toString() + "\r\n" + this.stackTrace.joinToString("\r\n") { it.toString() }
}

val Any.gson: Gson
    get() = GsonBuilder().create()


@Throws(IOException::class)
fun File.zip(targetFile: File) {
    ZipArchiveOutputStream(FileOutputStream(targetFile)).use { zipOut ->
        zipDirectoryHelper(this, this, zipOut)
    }
}

@Throws(IOException::class)
private fun zipDirectoryHelper(rootDir: File, currentDir: File, zipOut: ZipArchiveOutputStream) {
    currentDir.listFiles()?.let {
        for (file in it) {
            val entryName = rootDir.toPath().relativize(file.toPath()).toString().replace("\\", "/")
            if (file.isDirectory) {
                // 处理子目录
                zipDirectoryHelper(rootDir, file, zipOut)
            } else {
                // 添加文件到 ZIP
                val entry = ZipArchiveEntry(file, entryName)
                zipOut.putArchiveEntry(entry)
                FileInputStream(file).use { fis ->
                    IOUtils.copy(fis, zipOut)
                }
                zipOut.closeArchiveEntry()
            }
        }
    }

}