package com.lhstack.tools.listener;

import com.intellij.ide.AppLifecycleListener
import com.lhstack.tools.ToolsMainView
import com.lhstack.tools.actions.DeveloperPageAction

class JavaPluginAppLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ToolsMainView.actionSupplier = {panel,project ->
            DeveloperPageAction(panel,project)
        }
    }

}
