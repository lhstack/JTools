package com.lhstack.tools.listener;

import com.intellij.ide.AppLifecycleListener
import com.lhstack.tools.ToolsMainView
import com.lhstack.tools.actions.DeveloperPageAction
import com.lhstack.tools.db.AgentPersistence

class JavaPluginAppLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        AgentPersistence.bootstrap()
        ToolsMainView.actionSupplier = { panel, project ->
            DeveloperPageAction(panel, project)
        }
    }

}
