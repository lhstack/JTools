<script setup>
import { reactive, watch } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { MODALITY_VALUES } from '../../modelParamSchema.js'
import { useI18n } from '../../composables/useI18n.js'

const { t } = useI18n()

const props = defineProps({
  field: { type: Object, required: true },
  modelParams: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
  fields: { type: Array, default: () => [] }
})

const jsonTexts = reactive({})
const jsonErrors = reactive({})

function translated(path, defaultText) {
  const value = t(path)
  return value === path ? defaultText : value
}

function labelKey(key) {
  return String(key || '').replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_|_$/g, '')
}

function fieldLabel(field) {
  const translationKey = field.label || field.key
  return translated(`models.paramFields.${labelKey(translationKey)}`, field.label || field.key)
}

function fieldDescription(field) {
  const translationKey = field.label || field.key
  return translated(`models.paramFieldTips.${labelKey(translationKey)}`, '')
}

function valueLabel(value) {
  return translated(`models.paramValues.${labelKey(value)}`, value)
}

function valueAt(target, path) {
  return path.split('.').reduce((value, key) => value?.[key], target)
}

function setValueAt(target, path, value) {
  const keys = path.split('.')
  let cursor = target
  keys.slice(0, -1).forEach((key) => {
    if (!cursor[key] || typeof cursor[key] !== 'object' || Array.isArray(cursor[key])) cursor[key] = {}
    cursor = cursor[key]
  })
  cursor[keys.at(-1)] = value
}

function deleteValueAt(target, path) {
  const keys = path.split('.')
  const parent = keys.slice(0, -1).reduce((value, key) => value?.[key], target)
  if (parent && typeof parent === 'object') delete parent[keys.at(-1)]
}

function fieldValue(field) {
  return valueAt(props.modelParams, field.key)
}

function setFieldValue(field, value) {
  setValueAt(props.modelParams, field.key, value)
  clearHiddenStructuredFields(field, value)
}

function clearHiddenStructuredFields(field, value) {
  if (value === 'json_schema') return
  if (field.key === 'response_format.type') deleteValueAt(props.modelParams, 'response_format.json_schema')
  if (field.key === 'text.format.type') {
    deleteValueAt(props.modelParams, 'text.format.name')
    deleteValueAt(props.modelParams, 'text.format.strict')
    deleteValueAt(props.modelParams, 'text.format.schema')
  }
  if (field.key === 'output_config.format.type') deleteValueAt(props.modelParams, 'output_config.format.schema')
  if (field.key === 'thinking.type' && !['enabled', 'adaptive'].includes(value)) {
    deleteValueAt(props.modelParams, 'thinking.budget_tokens')
    deleteValueAt(props.modelParams, 'thinking.display')
  }
  if (field.key === 'tool_choice.type' && value !== 'tool') {
    deleteValueAt(props.modelParams, 'tool_choice.name')
  }
}

function listValue(field) {
  const value = fieldValue(field)
  if (Array.isArray(value)) return value
  setFieldValue(field, [])
  return fieldValue(field)
}

function addListItem(field) {
  listValue(field).push('')
}

function removeListItem(field, index) {
  listValue(field).splice(index, 1)
}

function mapEntries(field) {
  const value = fieldValue(field)
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    setFieldValue(field, {})
    return []
  }
  return Object.entries(value).map(([key, val]) => ({ key, value: val }))
}

function setMapEntry(field, oldKey, key, value) {
  const next = { ...(fieldValue(field) || {}) }
  if (oldKey && oldKey !== key) delete next[oldKey]
  if (key) next[key] = field.type === 'map-number' ? Number(value) : value
  setFieldValue(field, next)
}

function addMapEntry(field) {
  const next = { ...(fieldValue(field) || {}) }
  let index = Object.keys(next).length + 1
  while (Object.prototype.hasOwnProperty.call(next, `key_${index}`)) index += 1
  next[`key_${index}`] = field.type === 'map-number' ? 0 : ''
  setFieldValue(field, next)
}

function removeMapEntry(field, key) {
  const next = { ...(fieldValue(field) || {}) }
  delete next[key]
  setFieldValue(field, next)
}

function jsonFieldKey(field) {
  return field.key
}

function jsonFieldText(field) {
  const key = jsonFieldKey(field)
  if (!Object.prototype.hasOwnProperty.call(jsonTexts, key)) {
    const value = fieldValue(field)
    jsonTexts[key] = value && typeof value === 'object'
      ? JSON.stringify(value, null, 2)
      : ''
  }
  return jsonTexts[key]
}

function clearJsonEditorState() {
  Object.keys(jsonTexts).forEach((key) => delete jsonTexts[key])
  Object.keys(jsonErrors).forEach((key) => delete jsonErrors[key])
}

watch(
  () => [props.modelParams, props.field.key],
  clearJsonEditorState
)

function setJsonFieldText(field, text) {
  const key = jsonFieldKey(field)
  jsonTexts[key] = text
  jsonErrors[key] = ''
  if (!String(text).trim()) {
    setFieldValue(field, {})
    return
  }
  try {
    const value = JSON.parse(text)
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      jsonErrors[key] = t('models.jsonObjectRequired')
      return
    }
    setFieldValue(field, value)
  } catch (err) {
    jsonErrors[key] = err.message
  }
}

function formatJsonField(field) {
  const key = jsonFieldKey(field)
  try {
    const source = jsonTexts[key] || JSON.stringify(fieldValue(field) || {}, null, 2)
    const value = source.trim() ? JSON.parse(source) : {}
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      jsonErrors[key] = t('models.jsonObjectRequired')
      return
    }
    jsonTexts[key] = JSON.stringify(value, null, 2)
    jsonErrors[key] = ''
    setFieldValue(field, value)
  } catch (err) {
    jsonErrors[key] = err.message
  }
}

function fieldLayoutClass(field) {
  return {
    'param-field': true,
    'number-field': field.type === 'number',
    'boolean-field': field.type === 'boolean',
    'json-field': field.type === 'json-object',
    'wide-field': ['string-list', 'map-number', 'map-string', 'modality-list'].includes(field.type),
    'prompt-cache-key-field': field.key === 'prompt_cache_key',
    'visibility-controller-field': fieldControlsVisibility(field),
    'tool-choice-expanded-field': isToolChoiceField(field) && toolChoiceMode() === 'tool'
  }
}

function fieldControlsVisibility(field) {
  return props.fields.some((candidate) => candidate.showWhen?.key === field.key)
}

function isJsonField(field) {
  return field.type === 'json-object'
}

function isToolChoiceField(field) {
  return field.type === 'tool-choice-openai' || field.type === 'tool-choice-responses'
}

function toolChoiceMode() {
  const value = props.modelParams.tool_choice
  if (typeof value === 'string') return value
  if (value?.type === 'function' || value?.type === 'tool') return 'tool'
  return ''
}

function setToolChoiceMode(field, mode) {
  if (!mode) {
    props.modelParams.tool_choice = ''
    return
  }
  if (mode === 'tool') {
    props.modelParams.tool_choice = field.type === 'tool-choice-responses'
      ? { type: 'function', name: toolChoiceName() || '' }
      : { type: 'function', function: { name: toolChoiceName() || '' } }
    return
  }
  props.modelParams.tool_choice = mode
}

function toolChoiceName() {
  const value = props.modelParams.tool_choice
  return value?.function?.name || value?.name || ''
}

function setToolChoiceName(field, name) {
  const value = props.modelParams.tool_choice
  if (field.type === 'tool-choice-responses') {
    props.modelParams.tool_choice = { type: 'function', name }
    return
  }
  if (value?.type === 'tool') {
    props.modelParams.tool_choice = { ...value, name }
    return
  }
  props.modelParams.tool_choice = { type: 'function', function: { name } }
}
</script>

<template>
  <el-form-item :class="fieldLayoutClass(field)">
    <template v-if="!isJsonField(field)" #label>
      <span class="field-label">
        <span>{{ fieldLabel(field) }}</span>
        <el-tooltip v-if="fieldDescription(field)" :content="fieldDescription(field)" placement="top" :show-after="200">
          <el-icon class="field-help-icon"><QuestionFilled /></el-icon>
        </el-tooltip>
      </span>
    </template>
    <el-switch
      v-if="field.type === 'boolean'"
      :model-value="fieldValue(field)"
      :disabled="disabled"
      @update:model-value="setFieldValue(field, $event)"
    />
    <el-input-number
      v-else-if="field.type === 'number'"
      :model-value="fieldValue(field)"
      :min="field.min"
      :max="field.max"
      :step="field.step || 1"
      :precision="field.step && field.step < 1 ? 2 : 0"
      :disabled="disabled"
      controls-position="right"
      @update:model-value="setFieldValue(field, $event)"
    />
    <el-select
      v-else-if="field.type === 'select'"
      :model-value="fieldValue(field)"
      clearable
      :disabled="disabled"
      @update:model-value="setFieldValue(field, $event)"
    >
      <el-option v-for="item in field.options" :key="item" :label="valueLabel(item)" :value="item" />
    </el-select>
    <el-checkbox-group
      v-else-if="field.type === 'modality-list'"
      :model-value="Array.isArray(fieldValue(field)) ? fieldValue(field) : []"
      class="modality-checks"
      :disabled="disabled"
      @update:model-value="setFieldValue(field, $event)"
    >
      <el-checkbox-button
        v-for="item in MODALITY_VALUES"
        :key="item"
        :value="item"
      >
        {{ t(`models.modality${item.charAt(0).toUpperCase()}${item.slice(1)}`) }}
      </el-checkbox-button>
    </el-checkbox-group>
    <div v-else-if="field.type === 'string-list'" class="list-field">
      <div v-for="(_, index) in listValue(field)" :key="index" class="inline-row">
        <el-input v-model="listValue(field)[index]" :disabled="disabled" />
        <el-button :disabled="disabled" @click="removeListItem(field, index)">{{ t('common.delete') }}</el-button>
      </div>
      <el-button :disabled="disabled" @click="addListItem(field)">{{ t('models.add') }}</el-button>
    </div>
    <div v-else-if="field.type === 'map-number' || field.type === 'map-string'" class="map-field">
      <div v-for="entry in mapEntries(field)" :key="entry.key" class="inline-row">
        <el-input
          :model-value="entry.key"
          :disabled="disabled"
          placeholder="key"
          @update:model-value="setMapEntry(field, entry.key, $event, entry.value)"
        />
        <el-input-number
          v-if="field.type === 'map-number'"
          :model-value="Number(entry.value)"
          :disabled="disabled"
          @update:model-value="setMapEntry(field, entry.key, entry.key, $event)"
        />
        <el-input
          v-else
          :model-value="entry.value"
          :disabled="disabled"
          placeholder="value"
          @update:model-value="setMapEntry(field, entry.key, entry.key, $event)"
        />
        <el-button :disabled="disabled" @click="removeMapEntry(field, entry.key)">{{ t('common.delete') }}</el-button>
      </div>
      <el-button :disabled="disabled" @click="addMapEntry(field)">{{ t('models.add') }}</el-button>
    </div>
    <div v-else-if="field.type === 'json-object'" class="json-field-editor">
      <div class="json-editor-head">
        <span class="field-label">
          <span>{{ fieldLabel(field) }}</span>
          <el-tooltip v-if="fieldDescription(field)" :content="fieldDescription(field)" placement="top" :show-after="200">
            <el-icon class="field-help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
        <el-button size="small" :disabled="disabled" @click="formatJsonField(field)">
          {{ t('models.formatJson') }}
        </el-button>
      </div>
      <el-input
        :model-value="jsonFieldText(field)"
        type="textarea"
        :rows="6"
        resize="vertical"
        :disabled="disabled"
        class="json-textarea"
        @update:model-value="setJsonFieldText(field, $event)"
      />
      <div v-if="jsonErrors[jsonFieldKey(field)]" class="json-error">{{ jsonErrors[jsonFieldKey(field)] }}</div>
    </div>
    <div v-else-if="isToolChoiceField(field)" class="tool-choice-field">
      <el-select :model-value="toolChoiceMode()" clearable :disabled="disabled" @update:model-value="setToolChoiceMode(field, $event)">
        <el-option :label="valueLabel('none')" value="none" />
        <el-option :label="valueLabel('auto')" value="auto" />
        <el-option :label="valueLabel('required')" value="required" />
        <el-option :label="valueLabel('tool')" value="tool" />
      </el-select>
      <el-input
        v-if="toolChoiceMode() === 'tool'"
        :model-value="toolChoiceName()"
        :disabled="disabled"
        :placeholder="t('models.paramFields.tool_name')"
        @update:model-value="setToolChoiceName(field, $event)"
      />
    </div>
    <el-input
      v-else
      :model-value="fieldValue(field)"
      clearable
      :disabled="disabled"
      @update:model-value="setFieldValue(field, $event)"
    />
  </el-form-item>
</template>

<style scoped>
.field-label {
  display: inline-flex;
  min-width: 0;
  max-width: 100%;
  align-items: center;
  gap: 4px;
}

.field-label > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-help-icon {
  flex: 0 0 auto;
  color: #94a3b8;
  font-size: 13px;
  cursor: help;
}

.list-field,
.map-field,
.tool-choice-field,
.json-field-editor {
  display: grid;
  gap: 8px;
  width: 100%;
}

.inline-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.list-field .inline-row {
  grid-template-columns: minmax(0, 1fr) auto;
}

.json-editor-head {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.json-editor-head .field-label {
  margin-right: auto;
}

.json-textarea :deep(textarea) {
  min-height: 132px;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
}

.json-error {
  color: #dc2626;
  font-size: 12px;
  line-height: 1.4;
}

.modality-checks {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 2px;
}

.modality-checks :deep(.el-checkbox-button) {
  margin: 0;
}

.modality-checks :deep(.el-checkbox-button__inner) {
  border-left: 1px solid var(--el-border-color);
  border-radius: 6px !important;
  padding: 7px 11px;
  font-size: 12px;
}
</style>
