package com.lhstack.tools.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lhstack.tools.agent.AgentProxyType
import com.lhstack.tools.agent.AgentWebSearchEngineState
import com.lhstack.tools.agent.AgentWebToolSupport
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.*
import com.lhstack.tools.plugins.pluginManager
import com.lhstack.tools.plugins.pluginState
import org.apache.commons.io.FileUtils
import org.jdesktop.swingx.VerticalLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*


class SettingAction(windowPanel: SimpleToolWindowPanel, val project: Project) : AbstractPageAction(
    { "设置" },
    Icons.settingIcon(), windowPanel
) {
    private val sectionBorderColor = JBColor(Color(0xDFE3EA), Color(0x4C5052))
    private val sectionBackground = JBColor(Color(0xFBFCFE), Color(0x313335))
    private var panel: JPanel = JPanel()

    private var scrollPane: JScrollPane? = null

    init {

        panel.layout = VerticalLayout()
        panel.add(buildGeneralSettingsPanel())
        panel.add(buildWebToolPanel())
        scrollPane = JBScrollPane(panel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
    }


    override fun getPanel(): JComponent {
        return scrollPane!!
    }

    private fun buildGeneralSettingsPanel(): JComponent {
        return JPanel(VerticalLayout(8)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 4, 10)
            add(groupTitle("基础设置", "插件基础行为与入口配置。"))
            add(buildPluginInstallPanel())
            add(buildConsolePanel())
            add(buildRepositoryPanel())
        }
    }

    private fun buildPluginInstallPanel(): JComponent {
        val textField = JBTextField(
            project.pluginState().pluginBasePath.replace("\\", "/")
        ).apply {
            toolTipText = project.pluginState().pluginBasePath.replace("\\", "/")
        }
        val chooseButton = JButton("选择目录").apply {
            preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
            addActionListener {
                project.chooseDirectory(
                    "选择插件安装目录",
                    VirtualFileManager.getInstance().findFileByUrl("file://${textField.toolTipText}")
                ) {
                    textField.text = it.presentableUrl
                    textField.toolTipText = it.presentableUrl
                    textField.revalidate()
                    textField.repaint()
                }
            }
        }
        val applyButton = JButton("应用").apply {
            preferredSize = Dimension(JBUI.scale(84), preferredSize.height)
            addActionListener {
                File(textField.toolTipText).catch {
                    val originPluginDir = File(this.pluginState().pluginBasePath)
                    if (this.absolutePath != originPluginDir.absolutePath) {
                        if (!this.exists()) {
                            this.mkdirs()
                        }
                        if (this.isFile) {
                            project.errorNotify("提示", "插件目录不能是一个文件,请检查你输入的地址")
                        }
                        //1. 拷贝插件到新目录
                        //2. 修改插件信息里面的安装目录
                        this.pluginState().plugins.forEach { (k, v) ->
                            try {
                                val classloader = this.pluginManager().classloaders.remove(v)
                                val oldFile = File(v.path)
                                val newFile = File(this.absolutePath, oldFile.name)
                                if (v.type.equalsAnyIgnoreCase("js")) {
                                    FileUtils.copyDirectory(oldFile, newFile)
                                } else {
                                    FileUtils.copyFile(oldFile, newFile)
                                }
                                v.path = newFile.absolutePath
                                this.pluginState().plugins[k] = v

                                this.pluginManager().classloaders[v] = classloader!!
                                classloader.reset(arrayListOf(newFile.toPath()))
                                oldFile.forceDelete()
                            } catch (e: Throwable) {
                                project.errorNotify(
                                    "迁移插件通知",
                                    "迁移插件失败,失败插件名称: ${v.name},插件版本: ${v.version},异常信息: ${e.fullMsg()}"
                                )
                            }
                        }
                        project.infoNotify("迁移插件通知", "迁移插件完毕")
                        this.pluginState().pluginBasePath = this.absolutePath.replace("\\", "/")
                    }
                }
            }
        }

        return sectionPanel("插件安装目录", "默认目录为 ${Const.JTOOLS_PLUGIN_HOME}/plugins") {
            JPanel(GridBagLayout()).apply {
                isOpaque = false
                val c = GridBagConstraints().apply {
                    gridy = 0
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(2, 0, 0, 8)
                }
                c.gridx = 0
                c.weightx = 1.0
                add(textField, c)
                c.gridx = 1
                c.weightx = 0.0
                add(chooseButton, c)
                c.gridx = 2
                add(applyButton, c)
            }
        }
    }

    private fun buildConsolePanel(): JComponent {
        val consoleCheckBox = JCheckBox("启用日志控制台", project.pluginState().consoleLogEnabled).apply {
            toolTipText = "启用或禁用JTools日志控制台，禁用后可减少资源占用"
            addActionListener {
                project.pluginState().consoleLogEnabled = isSelected
                if (isSelected) {
                    project.activeConsolePanel()
                    project.infoNotify("日志控制台", "日志控制台已启用")
                } else {
                    project.deActiveConsolePanel()
                    project.infoNotify("日志控制台", "日志控制台已禁用")
                }
            }
        }
        return sectionPanel("日志控制台", "关闭后可减少资源占用") {
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(consoleCheckBox, BorderLayout.WEST)
            }
        }
    }

    private fun buildRepositoryPanel(): JComponent {
        return sectionPanel("插件仓库", "查看可用插件仓库") {
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(HyperlinkLabel("https://github.com/orgs/jtools-plugins/repositories").apply {
                    setHyperlinkTarget("https://github.com/orgs/jtools-plugins/repositories")
                }, BorderLayout.WEST)
                this.add(HyperlinkLabel("跳转").apply {
                    this.setHyperlinkTarget("https://github.com/orgs/jtools-plugins/repositories")
                })
            }
        }
    }

    private fun buildWebToolPanel(): JComponent {
        return JPanel(VerticalLayout(8)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 10, 10, 10)
            add(groupTitle("Web 工具", "WebFetch / WebSearch 专用网络配置。"))
            add(buildWebToolProxyPanel())
            add(buildWebSearchEnginePanel())
        }
    }

    private fun buildWebToolProxyPanel(): JComponent {
        val state = project.pluginState()
        val proxyEnabledCheck = JCheckBox("启用代理", state.webToolProxyEnabled)
        val proxyTypeCombo = JComboBox(AgentProxyType.entries.toTypedArray()).apply {
            selectedItem = AgentProxyType.fromId(state.webToolProxyType)
        }
        val proxyHostField = JBTextField(state.webToolProxyHost)
        val proxyPortField = JBTextField(if (state.webToolProxyPort > 0) state.webToolProxyPort.toString() else "")
        val applyButton = JButton("应用").apply {
            preferredSize = Dimension(JBUI.scale(84), preferredSize.height)
        }

        fun updateEnabled() {
            val enabled = proxyEnabledCheck.isSelected
            proxyTypeCombo.isEnabled = enabled
            proxyHostField.isEnabled = enabled
            proxyPortField.isEnabled = enabled
        }

        proxyEnabledCheck.addActionListener { updateEnabled() }
        applyButton.addActionListener {
            val enabled = proxyEnabledCheck.isSelected
            val host = proxyHostField.text.trim()
            val port = proxyPortField.text.trim().toIntOrNull()
            if (enabled) {
                if (host.isBlank()) {
                    project.errorNotify("提示", "请填写 Web 工具代理主机")
                    return@addActionListener
                }
                if (port == null || port !in 1..65535) {
                    project.errorNotify("提示", "请填写有效的 Web 工具代理端口")
                    return@addActionListener
                }
            }
            state.webToolProxyEnabled = enabled
            state.webToolProxyType = (proxyTypeCombo.selectedItem as? AgentProxyType)?.id ?: AgentProxyType.HTTP.id
            state.webToolProxyHost = host
            state.webToolProxyPort = port ?: 0
            project.infoNotify("提示", "Web 工具代理配置已保存")
        }
        updateEnabled()

        return sectionPanel("代理设置", "仅作用于 WebFetch / WebSearch") {
            JPanel(GridBagLayout()).apply {
                isOpaque = false
                val c = GridBagConstraints().apply {
                    gridy = 0
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(2, 0, 6, 8)
                }

                c.gridx = 0
                c.weightx = 0.0
                add(proxyEnabledCheck, c)
                c.gridx = 1
                add(fieldLabel("类型"), c)
                c.gridx = 2
                c.weightx = 0.0
                c.ipadx = JBUI.scale(110)
                add(proxyTypeCombo, c)
                c.ipadx = 0
                c.gridx = 3
                c.weightx = 1.0
                add(Box.createHorizontalGlue(), c)

                c.gridy = 1
                c.gridx = 0
                c.weightx = 0.0
                c.insets = JBUI.insets(2, 0, 0, 8)
                add(fieldLabel("主机"), c)
                c.gridx = 1
                c.gridwidth = 2
                c.weightx = 1.0
                add(proxyHostField, c)
                c.gridx = 3
                c.gridwidth = 1
                c.weightx = 0.0
                add(fieldLabel("端口"), c)
                c.gridx = 4
                c.ipadx = JBUI.scale(72)
                add(proxyPortField, c)
                c.ipadx = 0
                c.gridx = 5
                add(applyButton, c)
            }
        }
    }

    private fun buildWebSearchEnginePanel(): JComponent {
        val state = project.pluginState()
        val listModel = DefaultListModel<AgentWebSearchEngineState>()
        AgentWebToolSupport.normalizeSearchEngines(state.webSearchEngineConfigs, state.webSearchEngines)
            .forEach { listModel.addElement(it) }
        val engineList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 5
            dragEnabled = true
            dropMode = DropMode.INSERT
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val engine = value as? AgentWebSearchEngineState
                    val text = if (engine == null) "" else "${engine.name}    ${engine.address}"
                    return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
                }
            }
        }
        fun saveEngines() {
            val engines = (0 until listModel.size()).map {
                AgentWebToolSupport.copySearchEngine(listModel.getElementAt(it))
            }
            state.webSearchEngineConfigs = AgentWebToolSupport.normalizeSearchEngines(engines, state.webSearchEngines)
        }
        engineList.transferHandler = SearchEngineListTransferHandler(engineList, listModel) { saveEngines() }

        val addButton = JButton("新增").apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener {
                val created = showSearchEngineDialog("新增搜索引擎", null) ?: return@addActionListener
                listModel.addElement(created)
                engineList.selectedIndex = listModel.size() - 1
                saveEngines()
                project.infoNotify("提示", "WebSearch 搜索引擎已新增")
            }
        }
        val editButton = JButton("编辑").apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener {
                val selected = engineList.selectedValue ?: return@addActionListener
                val edited = showSearchEngineDialog("编辑搜索引擎", selected) ?: return@addActionListener
                val index = engineList.selectedIndex
                listModel.set(index, edited)
                engineList.selectedIndex = index
                saveEngines()
                project.infoNotify("提示", "WebSearch 搜索引擎已更新")
            }
        }
        val deleteButton = JButton("删除").apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener {
                val index = engineList.selectedIndex
                if (index < 0) {
                    return@addActionListener
                }
                if (listModel.size() <= 1) {
                    project.errorNotify("提示", "请至少保留一个 WebSearch 搜索引擎")
                    return@addActionListener
                }
                listModel.remove(index)
                if (!listModel.isEmpty) {
                    engineList.selectedIndex = index.coerceAtMost(listModel.size() - 1)
                }
                saveEngines()
                project.infoNotify("提示", "WebSearch 搜索引擎已删除")
            }
        }

        return sectionPanel("WebSearch 引擎", "拖拽列表项可调整优先级，地址支持 ${AgentWebToolSupport.QUERY_PLACEHOLDER} 占位符") {
            JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                isOpaque = false
                val scroll = JBScrollPane(engineList).apply {
                    preferredSize = Dimension(720, 148)
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                }
                add(scroll, BorderLayout.CENTER)
                add(JPanel(VerticalLayout(6)).apply {
                    isOpaque = false
                    preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
                    add(addButton)
                    add(editButton)
                    add(deleteButton)
                }, BorderLayout.EAST)
            }
        }
    }

    private fun showSearchEngineDialog(
        title: String,
        initial: AgentWebSearchEngineState?,
    ): AgentWebSearchEngineState? {
        val nameField = JBTextField(initial?.name.orEmpty()).apply { columns = 24 }
        val addressField = JBTextField(initial?.address.orEmpty()).apply {
            columns = 46
            toolTipText = "使用 ${AgentWebToolSupport.QUERY_PLACEHOLDER} 表示搜索词，例如 https://www.bing.com/search?q={query}"
        }
        val form = JPanel(VerticalLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("名称: "))
                add(nameField)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("地址: "))
                add(addressField)
            })
        }
        val result = JOptionPane.showConfirmDialog(
            panel,
            form,
            title,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) {
            return null
        }
        val normalized = AgentWebToolSupport.normalizeSearchEngine(
            AgentWebSearchEngineState().apply {
                name = nameField.text
                address = addressField.text
            }
        )
        if (normalized == null) {
            project.errorNotify("提示", "请填写搜索引擎地址")
            return null
        }
        return normalized
    }

    private class SearchEngineListTransferHandler(
        private val list: JList<AgentWebSearchEngineState>,
        private val model: DefaultListModel<AgentWebSearchEngineState>,
        private val onMoved: () -> Unit,
    ) : TransferHandler() {
        override fun getSourceActions(c: JComponent?): Int = MOVE

        override fun createTransferable(c: JComponent?): Transferable? {
            val index = list.selectedIndex
            return if (index < 0) null else StringSelection(index.toString())
        }

        override fun canImport(support: TransferHandler.TransferSupport): Boolean {
            return support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)
        }

        override fun importData(support: TransferHandler.TransferSupport): Boolean {
            if (!canImport(support)) {
                return false
            }
            val sourceIndex = runCatching {
                support.transferable.getTransferData(DataFlavor.stringFlavor).toString().toInt()
            }.getOrNull() ?: return false
            val dropLocation = support.dropLocation as? JList.DropLocation ?: return false
            val targetIndex = dropLocation.index.coerceIn(0, model.size())
            if (sourceIndex == targetIndex || sourceIndex !in 0 until model.size()) {
                return false
            }
            val item = model.getElementAt(sourceIndex)
            model.remove(sourceIndex)
            val insertIndex = if (sourceIndex < targetIndex) targetIndex - 1 else targetIndex
            model.add(insertIndex.coerceIn(0, model.size()), item)
            list.selectedIndex = insertIndex.coerceIn(0, model.size() - 1)
            onMoved()
            return true
        }
    }

    private fun sectionPanel(title: String, hint: String? = null, contentFactory: () -> JComponent): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = sectionBackground
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(sectionBorderColor),
                JBUI.Borders.empty(10, 12)
            )
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(JLabel(title).apply {
//                        font = font.deriveFont((font.style or java.awt.Font.BOLD).toFloat())
                        hint?.let { toolTipText = it }
                    })
                    hint?.takeIf { it.isNotBlank() }?.let { add(helpLabel(title, it)) }
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(contentFactory(), BorderLayout.CENTER)
        }
    }

    private fun groupTitle(title: String, hint: String): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(JLabel(title))
            add(helpLabel(title, hint))
        }
    }

    private fun helpLabel(title: String, text: String): JLabel {
        return JLabel(Icons.helpIcon()).apply {
            toolTipText = text
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        project.infoNotify(title, text)
                    }
                }
            })
        }
    }

    private fun fieldLabel(text: String): JLabel {
        return JLabel("$text:").apply {
            border = JBUI.Borders.emptyRight(4)
        }
    }

}
