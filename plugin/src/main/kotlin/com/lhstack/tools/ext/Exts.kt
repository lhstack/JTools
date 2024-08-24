package com.lhstack.tools.ext

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.lhstack.tools.ToolsMainWindowFactory
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import java.io.File


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

fun Project.chooseJarFile(consumer: (VirtualFile) -> Unit) {
    FileChooser.chooseFile(FileChooserDescriptor(false, true, true, true, false, false), this, null)
        ?.let { consumer(it) }
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

    }
}