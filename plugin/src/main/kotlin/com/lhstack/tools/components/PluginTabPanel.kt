package com.lhstack.tools.components

import com.intellij.ui.tabs.JBEditorTabsBase
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import javax.swing.JComponent
import javax.swing.JPanel

class PluginTabPanel(val pluginInfo: PluginInfo, val plugin: IPlugin,val pluginPanel:JComponent,var tabsPanel: JBEditorTabsBase) : JPanel() {

}