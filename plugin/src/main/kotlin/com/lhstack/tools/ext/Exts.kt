package com.lhstack.tools.ext

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.stream
import com.lhstack.tools.ToolsMainWindowFactory
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.nio.file.Path
import java.util.stream.Collectors


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


fun Any.infoNotify(title: String, msg: String) {
    this.notify(title, msg, NotificationType.INFORMATION)
}

fun Any.errorNotify(title: String, msg: String) {
    this.notify(title, msg, NotificationType.ERROR)
}

fun Any.notify(title: String, msg: String, notificationType: NotificationType) {
    if (this is Project) {
        Notifications.Bus.notify(
            Notification("ToolsNotification", title, msg, notificationType).setIcon(
                IconLoader.findIcon("/icons/notification.svg", ToolsMainWindowFactory::class.java)
            ), this
        )
    } else {
        Notifications.Bus.notify(
            Notification("ToolsNotification", title, msg, notificationType).setIcon(
                IconLoader.findIcon("/icons/notification.svg", ToolsMainWindowFactory::class.java)
            )
        )
    }
}

fun Any.findIcon(iconPath: String) = IconLoader.findIcon(iconPath, ToolsMainWindowFactory::class.java)

fun String.substr(start: Int, end: Int): String {
    if (this.length < end) {
        return this
    }
    return this.substring(start, end)
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
    try {
        FileUtils.forceDelete(this)
    } catch (ignore: Throwable) {
        ignore.message?.let {
            this.errorNotify(
                "插件文件删除失败,也许插件被其他进程占用了,或插件本身占用了,请检查你的插件是否存在有被引用的情况",
                it
            )
        }
    }
}