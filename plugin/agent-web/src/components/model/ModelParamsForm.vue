<script setup>
import { computed, ref } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { executionParamFields, modelParamFields, SESSION_RUNTIME_FIELDS } from '../../modelParamSchema.js'
import { useI18n } from '../../composables/useI18n.js'
import ModelParamField from './ModelParamField.vue'

const { t } = useI18n()

const PARAM_GROUP_ORDER = [
  'sampling',
  'limits',
  'reasoning',
  'structuredOutput',
  'tools',
  'streamingCache',
  'providerExtensions',
  'advanced'
]

const MODEL_FIELD_DISPLAY_GROUPS = {
  verbosity: 'execution',
  'text.verbosity': 'execution'
}

const CATALOG_MODEL_FIELD_KEYS = new Set([
  'max_tokens',
  'max_output_tokens',
  'max_completion_tokens',
  'stream',
  'thinking.type',
  'thinking.budget_tokens',
  'output_config.effort',
  'reasoning_effort',
  'reasoning.effort',
  'response_format.type',
  'response_format.json_schema.name',
  'response_format.json_schema.strict',
  'response_format.json_schema.schema',
  'parallel_tool_calls'
])

const SESSION_REASONING_FIELD_KEYS = new Set([
  'reasoning_effort',
  'reasoning.effort',
  'thinking.type',
  'thinking.budget_tokens',
  'thinking.display',
  'output_config.effort'
])

const props = defineProps({
  providerKind: { type: String, default: '' },
  apiType: { type: String, default: 'completions' },
  modelParams: { type: Object, required: true },
  executionParams: { type: Object, required: true },
  openaiCompatible: { type: Boolean, default: false },
  additionalParamsText: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  showAdditionalParams: { type: Boolean, default: true },
  compact: { type: Boolean, default: false },
  showPromptCacheKey: { type: Boolean, default: false },
  showSessionRuntime: { type: Boolean, default: false },
  showModelCapabilities: { type: Boolean, default: false },
  showAgentRuntime: { type: Boolean, default: false },
  hideReasoningFields: { type: Boolean, default: false },
  catalogAligned: { type: Boolean, default: false },
  sessionParams: { type: Object, default: null },
  modelCapabilities: { type: Object, default: null },
  agentRuntime: { type: Object, default: null }
})

const emit = defineEmits(['update:additionalParamsText'])
const additionalParamsError = ref('')

const fields = computed(() => modelParamFields(props.providerKind, props.apiType, props.openaiCompatible))
const sessionRuntimeFields = computed(() => {
  if (!props.showSessionRuntime) return []
  return SESSION_RUNTIME_FIELDS.filter((field) => {
    if (field.key === 'modalities' && !props.sessionParams) return false
    if (props.catalogAligned && field.key === 'max_history_messages') return false
    return true
  })
})
const visibleExecutionParamFields = computed(() => {
  if (!props.catalogAligned) return executionParamFields
  return executionParamFields.filter((field) => field.key !== 'tool_call_retention_rounds')
})
const modelCapabilityFields = computed(() => {
  if (!props.showModelCapabilities || !props.modelCapabilities) return []
  return [
    { key: 'context_window', label: 'context_window', type: 'number', min: 0, step: 1 },
    { key: 'modalities', label: 'modalities', type: 'modality-list' }
  ]
})
const agentRuntimeFields = computed(() => {
  if (!props.showAgentRuntime || !props.agentRuntime) return []
  return [
    { key: 'runtime_context_window', label: 'context_window', type: 'number', min: 0, step: 1 },
    { key: 'history_max_messages', label: 'max_history_messages', type: 'number', min: 0, step: 1 }
  ]
})
const commonFields = computed(() => fields.value.filter((field) => field.common && isFieldVisible(field)))
const groupedFields = computed(() => {
  const groups = new Map()
  for (const field of fields.value) {
    if (field.common || !isFieldVisible(field)) continue
    const group = displayGroup(field)
    if (group === 'execution') continue
    if (!groups.has(group)) groups.set(group, [])
    groups.get(group).push(field)
  }
  return PARAM_GROUP_ORDER
    .filter((group) => groups.has(group))
    .map((group) => ({ key: group, fields: groups.get(group) }))
})
const executionModelFields = computed(() => fields.value.filter((field) => isFieldVisible(field) && displayGroup(field) === 'execution'))
function displayGroup(field) {
  return MODEL_FIELD_DISPLAY_GROUPS[field.key] || field.group || 'advanced'
}

function translated(path, defaultText) {
  const value = t(path)
  return value === path ? defaultText : value
}

function labelKey(key) {
  return String(key || '').replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_|_$/g, '')
}

function groupLabel(group) {
  return translated(`models.paramGroups.${group}`, group)
}

function fieldLabel(field) {
  return translated(`models.paramFields.${labelKey(field.key)}`, field.label || field.key)
}

function fieldDescription(field) {
  return translated(`models.paramFieldTips.${labelKey(field.key)}`, '')
}

function valueAt(target, path) {
  return path.split('.').reduce((value, key) => value?.[key], target)
}

function isFieldVisible(field) {
  if (props.catalogAligned && !CATALOG_MODEL_FIELD_KEYS.has(field.key)) return false
  if (props.hideReasoningFields && SESSION_REASONING_FIELD_KEYS.has(field.key)) return false
  if (field.hiddenByDefault && field.key === 'prompt_cache_key' && !props.showPromptCacheKey) return false
  if (!field.showWhen) return true
  const current = valueAt(props.modelParams, field.showWhen.key)
  if (Array.isArray(field.showWhen.values)) return field.showWhen.values.includes(current)
  return current === field.showWhen.value
}

function executionValue(field) {
  return props.executionParams[field.key]
}

function setExecutionValue(field, value) {
  props.executionParams[field.key] = value
}

function updateAdditionalParamsText(text) {
  additionalParamsError.value = ''
  if (String(text).trim()) {
    try {
      const value = JSON.parse(text)
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        additionalParamsError.value = t('models.jsonObjectRequired')
      }
    } catch (err) {
      additionalParamsError.value = err.message
    }
  }
  emit('update:additionalParamsText', text)
}

function formatAdditionalParamsText() {
  try {
    const source = props.additionalParamsText || ''
    const value = source.trim() ? JSON.parse(source) : {}
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      additionalParamsError.value = t('models.jsonObjectRequired')
      return
    }
    additionalParamsError.value = ''
    emit('update:additionalParamsText', JSON.stringify(value, null, 2))
  } catch (err) {
    additionalParamsError.value = err.message
  }
}
</script>

<template>
  <div class="model-param-form" :class="{ compact }">
    <section v-if="commonFields.length || sessionRuntimeFields.length || modelCapabilityFields.length || agentRuntimeFields.length" class="param-group">
      <div class="param-grid">
        <ModelParamField
          v-for="field in commonFields"
          :key="field.key"
          :field="field"
          :fields="fields"
          :model-params="modelParams"
          :disabled="disabled"
        />
        <ModelParamField
          v-for="field in sessionRuntimeFields"
          :key="`session-${field.key}`"
          :field="field"
          :fields="sessionRuntimeFields"
          :model-params="sessionParams || {}"
          :disabled="disabled"
        />
        <ModelParamField
          v-for="field in modelCapabilityFields"
          :key="`capability-${field.key}`"
          :field="field"
          :fields="modelCapabilityFields"
          :model-params="modelCapabilities || {}"
          :disabled="disabled"
        />
        <ModelParamField
          v-for="field in agentRuntimeFields"
          :key="`agent-${field.key}`"
          :field="field"
          :fields="agentRuntimeFields"
          :model-params="agentRuntime || {}"
          :disabled="disabled"
        />
      </div>
    </section>

    <section v-if="executionModelFields.length || visibleExecutionParamFields.length" class="param-group execution-group">
      <div class="param-section-title">{{ t('models.paramGroups.execution') }}</div>
      <div class="param-grid">
        <ModelParamField
          v-for="field in executionModelFields"
          :key="field.key"
          :field="field"
          :fields="fields"
          :model-params="modelParams"
          :disabled="disabled"
        />
        <el-form-item v-for="field in visibleExecutionParamFields" :key="field.key" class="param-field number-field">
          <template #label>
            <span class="field-label">
              <span>{{ fieldLabel(field) }}</span>
              <el-tooltip v-if="fieldDescription(field)" :content="fieldDescription(field)" placement="top" :show-after="200">
                <el-icon class="field-help-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number
            :model-value="executionValue(field)"
            :min="field.min"
            :step="field.step"
            :disabled="disabled"
            controls-position="right"
            @update:model-value="setExecutionValue(field, $event)"
          />
        </el-form-item>
      </div>
    </section>

    <section v-for="group in groupedFields" :key="group.key" class="param-group">
      <div class="param-section-title">{{ groupLabel(group.key) }}</div>
      <div class="param-grid">
        <ModelParamField
          v-for="field in group.fields"
          :key="field.key"
          :field="field"
          :fields="fields"
          :model-params="modelParams"
          :disabled="disabled"
        />
      </div>
    </section>

    <section v-if="showAdditionalParams" class="param-group additional-params-group">
      <div class="param-section-title">{{ t('models.additionalParams') }}</div>
      <el-form-item class="additional-params-field">
        <div class="json-field-editor">
          <div class="json-editor-head">
            <span class="field-label">
              <span>仅填写未被表单覆盖的 JSON 参数</span>
              <el-tooltip :content="t('models.additionalParamsHelp')" placement="top" :show-after="200">
                <el-icon class="field-help-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
            <el-button size="small" :disabled="disabled" @click="formatAdditionalParamsText">
              {{ t('models.formatJson') }}
            </el-button>
          </div>
          <el-input
            :model-value="additionalParamsText"
            type="textarea"
            :rows="6"
            resize="vertical"
            :disabled="disabled"
            class="json-textarea"
            @update:model-value="updateAdditionalParamsText"
          />
          <div v-if="additionalParamsError" class="json-error">{{ additionalParamsError }}</div>
        </div>
      </el-form-item>
    </section>
  </div>
</template>

<style scoped>
.model-param-form {
  display: grid;
  gap: 16px;
}

.advanced-collapse {
  border: 0;
}

.advanced-collapse :deep(.el-collapse-item) {
  margin-bottom: 0;
  border: 1px solid var(--soft-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--jb-panel);
}

.advanced-collapse :deep(.el-collapse-item__header) {
  height: 38px;
  padding: 0 12px;
  border-bottom: 0;
  background: var(--soft-fill);
}

.advanced-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
  background: transparent;
}

.advanced-collapse :deep(.el-collapse-item__content) {
  display: grid;
  gap: 16px;
  padding: 12px 14px 14px;
  background: transparent;
  color: var(--jb-text);
}

.collapse-title {
  font-size: 13px;
  font-weight: 750;
}

.param-section-title {
  margin-top: 0;
  border-top: 1px solid var(--soft-border);
  padding-top: 12px;
  color: var(--jb-text);
  font-size: 13px;
  font-weight: 750;
}

.param-group:first-child .param-section-title {
  border-top: 0;
  padding-top: 0;
}

.param-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px 12px;
  align-items: start;
}

.model-param-form.compact .param-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

:deep(.json-field),
:deep(.wide-field),
:deep(.visibility-controller-field),
:deep(.tool-choice-expanded-field) {
  grid-column: 1 / -1;
}

:deep(.prompt-cache-key-field) {
  grid-column: span 2;
}

:deep(.boolean-field .el-form-item__content) {
  min-height: 32px;
  align-items: center;
}

@media (max-width: 900px) {
  .param-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  :deep(.prompt-cache-key-field) {
    grid-column: 1 / -1;
  }
}

@media (max-width: 560px) {
  .param-grid,
  .model-param-form.compact .param-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.model-param-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.additional-params-field {
  border-top: 1px solid var(--soft-border);
  padding-top: 12px;
}

.model-param-form :deep(.el-input-number),
.model-param-form :deep(.el-select),
.model-param-form :deep(.el-input) {
  width: 100%;
}

.model-param-form :deep(.el-form-item__label) {
  min-width: 0;
  line-height: 1.2;
}

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

.model-param-form :deep(.el-input-number .el-input__inner) {
  text-align: left;
}

:deep(.list-field),
:deep(.map-field),
:deep(.tool-choice-field),
.json-field-editor {
  display: grid;
  gap: 8px;
  width: 100%;
}

:deep(.inline-row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

:deep(.list-field .inline-row) {
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
</style>
