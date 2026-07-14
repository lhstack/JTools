package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.GsonBuilder
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.*

private val MODEL_PARAMS_GSON = GsonBuilder().setPrettyPrinting().create()

/** 单个模型参数字段的元信息。key 支持点号表示嵌套路径。 */
private data class SwingParamField(
    val key: String,
    val group: String,
    val type: String,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val showWhen: (() -> Boolean)? = null,
)

private const val COLUMNS = 4
private val GROUP_ORDER = listOf("limits", "reasoning", "tools", "streaming")

/**
 * 简化后的模型参数面板。只暴露约定的少量参数：
 * 最大 token、推理、并行工具调用、流式输出（model_params），
 * 工具调用轮次、重试次数（execution_params），额外参数 JSON（additional_params）。
 * 缓存、采样、结构化输出等参数不暴露，走默认。上下文窗口由外层面板单独维护。
 */
class AwakeModelParamsPanel {

    private val root = JPanel(BorderLayout())
    private var providerKind = ""
    private var apiType = "completions"
    private var openaiCompatible = false
    private var modelParams = JsonObject()
    private var executionParams = JsonObject()
    private var additionalParams = JsonObject()

    val component: JComponent get() = root

    init {
        root.border = JBUI.Borders.empty(4)
    }

    // -------- 公开加载 / 导出 --------

    fun load(
        providerKind: String,
        apiType: String,
        openaiCompatible: Boolean,
        modelParams: String?,
        executionParams: String?,
        additionalParams: String?,
    ) {
        this.providerKind = providerKind
        this.apiType = apiType
        this.openaiCompatible = openaiCompatible
        this.modelParams = parseObject(modelParams)
        this.executionParams = parseObject(executionParams)
        this.additionalParams = parseObject(additionalParams)
        applyDefaults()
        rebuild()
    }

    fun reload(providerKind: String, apiType: String, openaiCompatible: Boolean) {
        this.providerKind = providerKind
        this.apiType = apiType
        this.openaiCompatible = openaiCompatible
        rebuild()
    }

    fun modelParamsJson(): String = MODEL_PARAMS_GSON.toJson(prune(modelParams) ?: JsonObject())

    fun executionParamsJson(): String {
        val result = JsonObject()
        numberValue(executionParams, "max_tool_call_rounds")?.takeIf { it != 30L }
            ?.let { result.addProperty("max_tool_call_rounds", it) }
        numberValue(executionParams, "max_retries")?.takeIf { it != 0L }
            ?.let { result.addProperty("max_retries", it) }
        return MODEL_PARAMS_GSON.toJson(result)
    }

    fun additionalParamsJson(): String = MODEL_PARAMS_GSON.toJson(prune(additionalParams) ?: JsonObject())

    // -------- 默认值 --------

    private fun applyDefaults() {
        if (providerKind == "anthropic" && !modelParams.has("max_tokens")) {
            modelParams.addProperty("max_tokens", 4096)
        }
        if (!modelParams.has("stream")) modelParams.addProperty("stream", true)
        if (!executionParams.has("max_tool_call_rounds")) executionParams.addProperty("max_tool_call_rounds", 30)
        if (!executionParams.has("max_retries")) executionParams.addProperty("max_retries", 0)
    }

    // -------- 字段清单 --------

    private fun fields(): List<SwingParamField> {
        val base = if (providerKind == "anthropic") anthropicFields() else openAiFields()
        return base + SwingParamField("additional_params", "additional", "json-object")
    }

    private fun openAiFields(): List<SwingParamField> = buildList {
        if (apiType == "responses") {
            add(SwingParamField("max_output_tokens", "limits", "number", min = 1.0))
            add(SwingParamField("reasoning.effort", "reasoning", "select", REASONING_EFFORTS))
        } else {
            add(SwingParamField("max_completion_tokens", "limits", "number", min = 1.0))
            add(SwingParamField("reasoning_effort", "reasoning", "select", REASONING_EFFORTS))
        }
        if (openaiCompatible) {
            add(SwingParamField("thinking.type", "reasoning", "select", listOf("enabled", "disabled")))
            add(SwingParamField("output_config.effort", "reasoning", "select", OUTPUT_EFFORTS))
        }
        add(SwingParamField("parallel_tool_calls", "tools", "boolean"))
        add(SwingParamField("stream", "streaming", "boolean"))
    }

    private fun anthropicFields(): List<SwingParamField> = buildList {
        add(SwingParamField("max_tokens", "limits", "number", min = 1.0))
        add(SwingParamField("thinking.type", "reasoning", "select", listOf("enabled", "disabled", "adaptive")))
        add(SwingParamField(
            "thinking.budget_tokens", "reasoning", "number", min = 1.0,
            showWhen = { stringValue(modelParams, "thinking.type") in listOf("enabled", "adaptive") },
        ))
        add(SwingParamField("tool_choice.disable_parallel_tool_use", "tools", "boolean"))
        add(SwingParamField("stream", "streaming", "boolean"))
    }

    // -------- 界面构建 --------

    private fun rebuild() {
        root.removeAll()
        val body = JPanel(GridBagLayout())
        val visibleFields = fields().filter { it.showWhen?.invoke() ?: true }
        val sections = mutableListOf<JComponent>()
        GROUP_ORDER.forEach { group ->
            val groupFields = visibleFields.filter { it.group == group }
            if (groupFields.isNotEmpty()) sections.add(paramSection(group, groupFields))
        }
        sections.add(executionSection())
        visibleFields.filter { it.group == "additional" }
            .takeIf { it.isNotEmpty() }
            ?.let { sections.add(paramSection("additional", it)) }
        sections.forEachIndexed { index, section ->
            body.add(section, GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
            })
        }
        root.add(body, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun paramSection(group: String, fields: List<SwingParamField>): JComponent =
        titledGrid(groupLabel(group), fields.map { fieldComponent(it) to (it.type == "json-object") })

    private fun executionSection(): JComponent {
        val cells = listOf(
            labeledEditor("最大工具调用轮次", numberField(numberValue(executionParams, "max_tool_call_rounds")) { value ->
                value?.takeIf { it >= 1 }?.let { executionParams.addProperty("max_tool_call_rounds", it) }
            }) to false,
            labeledEditor("最大重试次数", numberField(numberValue(executionParams, "max_retries")) { value ->
                value?.takeIf { it >= 0 }?.let { executionParams.addProperty("max_retries", it) }
            }) to false,
        )
        return titledGrid(groupLabel("execution"), cells)
    }

    private fun titledGrid(title: String, cells: List<Pair<JComponent, Boolean>>): JComponent =
        JPanel(BorderLayout(0, 4)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
            border = JBUI.Borders.empty(6, 2)
            add(JLabel(title).apply {
                horizontalAlignment = SwingConstants.LEFT
                alignmentX = JComponent.LEFT_ALIGNMENT
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }, BorderLayout.NORTH)
            add(gridOf(cells), BorderLayout.CENTER)
        }

    private fun gridOf(cells: List<Pair<JComponent, Boolean>>): JComponent = JPanel(GridBagLayout()).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        isOpaque = false
        var row = 0
        var column = 0
        cells.forEach { (cell, fullWidth) ->
            if (fullWidth && column != 0) {
                row++
                column = 0
            }
            add(cell, GridBagConstraints().apply {
                gridx = if (fullWidth) 0 else column
                gridy = row
                gridwidth = if (fullWidth) COLUMNS else 1
                weightx = if (fullWidth) 1.0 else 1.0 / COLUMNS
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(JBUI.scale(3), JBUI.scale(4), JBUI.scale(3), JBUI.scale(4))
            })
            if (fullWidth || column == COLUMNS - 1) {
                row++
                column = 0
            } else {
                column++
            }
        }
        add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = COLUMNS
            weightx = 1.0
            weighty = 1.0
            fill = GridBagConstraints.VERTICAL
        })
    }

    private fun fieldComponent(field: SwingParamField): JComponent {
        val editor = when (field.type) {
            "boolean" -> JCheckBox().apply {
                isSelected = booleanValue(modelParams, field.key)
                addActionListener { setBoolean(field.key, isSelected) }
            }
            "number" -> JBTextField(numberValue(modelParams, field.key)?.toString().orEmpty()).apply {
                bindNumberChanges(this) { setNumber(field.key, it, field) }
            }
            "select" -> selectCombo(field)
            "json-object" -> return additionalJsonEditorCell()
            else -> JBTextField(stringValue(modelParams, field.key).orEmpty()).apply {
                addActionListener { setString(field.key, text) }
            }
        }
        return labeledEditor(fieldLabel(field.key), editor)
    }

    private fun selectCombo(field: SwingParamField): JComboBox<String> {
        val labels = (listOf("") + field.options).map { optionLabel(field.key, it) }
        return JComboBox(labels.toTypedArray()).apply {
            selectedItem = optionLabel(field.key, stringValue(modelParams, field.key).orEmpty())
            addActionListener {
                val selected = (listOf("") + field.options)
                    .firstOrNull { optionLabel(field.key, it) == selectedItem?.toString() }
                    .orEmpty()
                setString(field.key, selected)
                if (isVisibilityController(field.key)) rebuild()
            }
        }
    }

    private fun labeledEditor(label: String, editor: JComponent): JComponent {
        val body = JPanel(BorderLayout(0, 3)).apply {
            isOpaque = false
            add(JLabel(label), BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3)
            add(body, BorderLayout.NORTH)
        }
    }

    private fun numberField(current: Long?, commit: (Long?) -> Unit): JComponent =
        JBTextField(current?.toString().orEmpty()).apply {
            bindNumberChanges(this) { commit(it.trim().toLongOrNull()) }
        }

    /** 数字输入在编辑、回车、失焦三种时机统一提交，避免保存时丢失尚未回车的值。 */
    private fun bindNumberChanges(field: JTextField, commit: (String) -> Unit) {
        fun update() = commit(field.text)
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(event: javax.swing.event.DocumentEvent) = update()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent) = update()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent) = update()
        })
        field.addActionListener { update() }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) = update()
        })
    }

    private fun additionalJsonEditorCell(): JComponent {
        val area = JBTextArea(5, 0).apply {
            lineWrap = false
            text = MODEL_PARAMS_GSON.toJson(additionalParams)
            border = JBUI.Borders.empty(4)
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(event: javax.swing.event.DocumentEvent) = commit()
                override fun removeUpdate(event: javax.swing.event.DocumentEvent) = commit()
                override fun changedUpdate(event: javax.swing.event.DocumentEvent) = commit()
                private fun commit() {
                    val value = runCatching { JsonParser.parseString(text.ifBlank { "{}" }) }.getOrNull() ?: return
                    if (value.isJsonObject) additionalParams = value.asJsonObject
                }
            })
        }
        val scroll = JBScrollPane(area).apply {
            preferredSize = java.awt.Dimension(0, JBUI.scale(125))
            minimumSize = java.awt.Dimension(0, JBUI.scale(125))
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        return labeledEditor(fieldLabel("additional_params"), scroll)
    }

    // -------- 取值 / 赋值 --------

    private fun isVisibilityController(key: String): Boolean =
        fields().any { it.showWhen != null && it.key != key && it.key.startsWith(key.substringBefore('.')) }

    private fun setString(path: String, value: String) {
        if (value.isBlank()) remove(path) else set(path, JsonPrimitive(value))
    }

    private fun setBoolean(path: String, value: Boolean) = set(path, JsonPrimitive(value))

    private fun setNumber(path: String, value: String, field: SwingParamField) {
        val number = value.trim().toDoubleOrNull() ?: run { remove(path); return }
        if (field.min != null && number < field.min) return
        if (field.max != null && number > field.max) return
        set(path, if (number % 1 == 0.0) JsonPrimitive(number.toLong()) else JsonPrimitive(number))
    }

    private fun set(path: String, value: JsonElement) {
        val keys = path.split('.')
        var cursor = modelParams
        keys.dropLast(1).forEach { key ->
            cursor = cursor.getAsJsonObject(key) ?: JsonObject().also { cursor.add(key, it) }
        }
        cursor.add(keys.last(), value)
    }

    private fun remove(path: String) {
        val keys = path.split('.')
        val parent = keys.dropLast(1).fold(modelParams as JsonObject?) { current, key -> current?.getAsJsonObject(key) }
        parent?.remove(keys.last())
    }

    private fun parseObject(text: String?): JsonObject = runCatching {
        val element = JsonParser.parseString(text?.ifBlank { "{}" } ?: "{}")
        if (element.isJsonObject) element.asJsonObject else JsonObject()
    }.getOrDefault(JsonObject())

    private fun stringValue(root: JsonObject, path: String): String? =
        elementValue(root, path)?.takeIf { it.isJsonPrimitive }?.asString

    private fun booleanValue(root: JsonObject, path: String): Boolean =
        elementValue(root, path)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun numberValue(root: JsonObject, path: String): Long? =
        elementValue(root, path)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun elementValue(root: JsonObject, path: String): JsonElement? {
        var current: JsonElement = root
        path.split('.').forEach { key ->
            current = current.takeIf { it.isJsonObject }?.asJsonObject?.get(key) ?: return null
        }
        return current
    }

    private fun prune(element: JsonElement): JsonElement? {
        if (element.isJsonObject) {
            val result = JsonObject()
            element.asJsonObject.entrySet().forEach { (key, value) -> prune(value)?.let { result.add(key, it) } }
            return result.takeIf { it.size() > 0 }
        }
        if (element.isJsonArray) {
            val result = JsonArray()
            element.asJsonArray.forEach { prune(it)?.let(result::add) }
            return result.takeIf { it.size() > 0 }
        }
        if (element.isJsonNull || (element.isJsonPrimitive && element.asString.isBlank())) return null
        return element
    }

    // -------- 文案 --------

    private fun groupLabel(group: String): String = when (group) {
        "limits" -> "最大 Token"
        "reasoning" -> "推理"
        "tools" -> "工具调用"
        "streaming" -> "流式输出"
        "execution" -> "执行控制"
        "additional" -> "额外参数"
        else -> group
    }

    private fun fieldLabel(key: String): String = when (key) {
        "max_completion_tokens" -> "最大补全 Token 数"
        "max_output_tokens" -> "最大输出 Token 数"
        "max_tokens" -> "最大 Token 数"
        "reasoning_effort", "reasoning.effort" -> "推理强度"
        "thinking.type" -> "思考模式"
        "thinking.budget_tokens" -> "思考 Token 预算"
        "output_config.effort" -> "输出推理强度"
        "parallel_tool_calls" -> "允许并行工具调用"
        "tool_choice.disable_parallel_tool_use" -> "禁止并行工具调用"
        "stream" -> "流式输出"
        "additional_params" -> "额外参数（JSON）"
        else -> key
    }

    private fun optionLabel(key: String, value: String): String = when (key) {
        "reasoning_effort", "reasoning.effort" -> when (value) {
            "" -> "未设置"; "none" -> "不推理"; "minimal" -> "极低"; "low" -> "低"
            "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; else -> value
        }
        "thinking.type" -> when (value) {
            "" -> "未设置"; "enabled" -> "启用"; "disabled" -> "禁用"; "adaptive" -> "自适应"; else -> value
        }
        "output_config.effort" -> when (value) {
            "" -> "未设置"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; "max" -> "最大"; else -> value
        }
        else -> value.ifBlank { "未设置" }
    }

    private companion object {
        val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh")
        val OUTPUT_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}
