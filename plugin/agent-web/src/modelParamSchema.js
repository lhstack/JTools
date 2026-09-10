export function effectiveProviderApi(providerKind, apiType) {
  if (providerKind === 'anthropic') return 'messages'
  return apiType === 'responses' ? 'responses' : 'completions'
}

export function modelParamFields(providerKind, apiType, openaiCompatible = false) {
  const api = effectiveProviderApi(providerKind, apiType)
  if (providerKind === 'anthropic') return anthropicFields
  const fields = api === 'responses' ? openAiResponsesFields : openAiChatFields
  return openaiCompatible ? [...fields, ...openAiCompatibleFields] : fields
}

export const executionParamFields = [
  { key: 'max_tool_call_rounds', label: 'max_tool_call_rounds', type: 'number', min: 1, step: 1 },
  { key: 'max_retries', label: 'max_retries', type: 'number', min: 0, step: 1 },
  { key: 'tool_call_retention_rounds', label: 'tool_call_retention_rounds', type: 'number', min: 0, step: 1 }
]

export const DEFAULT_CONTEXT_WINDOW = 32000
export const DEFAULT_MAX_HISTORY_MESSAGES = 0
export const DEFAULT_MAX_TOOL_CALL_ROUNDS = 30
export const DEFAULT_MAX_RETRIES = 0
export const DEFAULT_MODALITIES = ['text']
export const DEFAULT_ANTHROPIC_MAX_TOKENS = 4096
export const SESSION_RUNTIME_FIELDS = [
  { key: 'context_window', label: 'context_window', type: 'number', min: 0, step: 1, common: true },
  { key: 'max_history_messages', label: 'max_history_messages', type: 'number', min: 0, step: 1, common: true },
  { key: 'modalities', label: 'modalities', type: 'modality-list', common: true }
]
export const MODALITY_VALUES = ['text', 'image', 'audio', 'video', 'file']

export function defaultModelParams(providerKind, apiType = 'completions') {
  if (providerKind === 'anthropic') {
    return {
      max_tokens: DEFAULT_ANTHROPIC_MAX_TOKENS,
      stream: true
    }
  }
  if (apiType === 'responses') {
    return {
      stream: true,
      parallel_tool_calls: true,
      reasoning: {
        summary: 'auto',
        context: 'auto'
      }
    }
  }
  return {
    stream: true,
    parallel_tool_calls: true,
    stream_options: {
      include_usage: true
    }
  }
}

export function defaultExecutionParams() {
  return {
    max_tool_call_rounds: DEFAULT_MAX_TOOL_CALL_ROUNDS,
    max_retries: DEFAULT_MAX_RETRIES
  }
}

export function executionParamsFromConfigItems(items = []) {
  const defaults = defaultExecutionParams()
  for (const item of items) {
    if (item.key !== 'model.max_tool_call_rounds' && item.key !== 'model.max_retries') continue
    const value = Number(item.value)
    if (!Number.isFinite(value)) continue
    if (item.key === 'model.max_tool_call_rounds') defaults.max_tool_call_rounds = value
    if (item.key === 'model.max_retries') defaults.max_retries = value
  }
  return defaults
}

export function modelParamsWithDefaults(providerKind, apiType, value) {
  return {
    ...defaultModelParams(providerKind, apiType),
    ...clonePlainObject(value)
  }
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

export function sanitizeModelParamsForApi(providerKind, apiType, value, openaiCompatible = false) {
  const source = clonePlainObject(value)
  const next = {}
  for (const field of modelParamFields(providerKind, apiType, openaiCompatible)) {
    const fieldValue = valueAt(source, field.key)
    if (fieldValue === undefined) continue
    setValueAt(next, field.key, fieldValue)
  }
  return next
}

export function executionParamsWithDefaults(value, defaults = defaultExecutionParams()) {
  return {
    ...defaults,
    ...clonePlainObject(value)
  }
}

export function executionParamsForApi(value, defaults = defaultExecutionParams()) {
  const source = clonePlainObject(value)
  const next = {}
  for (const field of executionParamFields) {
    const value = source[field.key]
    if (value === '' || value === null || value === undefined) continue
    if (value === defaults[field.key]) continue
    next[field.key] = value
  }
  return Object.keys(next).length ? next : null
}

export function defaultContextWindow(value) {
  return value ?? DEFAULT_CONTEXT_WINDOW
}

export function defaultMaxHistoryMessages(value) {
  return value ?? DEFAULT_MAX_HISTORY_MESSAGES
}

export function defaultModalities(value) {
  return Array.isArray(value) && value.length ? value : [...DEFAULT_MODALITIES]
}

const openAiChatFields = [
  { key: 'temperature', label: 'temperature', group: 'sampling', type: 'number', min: 0, max: 2, step: 0.1, common: true },
  { key: 'top_p', label: 'top_p', group: 'sampling', type: 'number', min: 0, max: 1, step: 0.1 },
  { key: 'frequency_penalty', label: 'frequency_penalty', group: 'sampling', type: 'number', min: -2, max: 2, step: 0.1 },
  { key: 'presence_penalty', label: 'presence_penalty', group: 'sampling', type: 'number', min: -2, max: 2, step: 0.1 },
  { key: 'logit_bias', label: 'logit_bias', group: 'sampling', type: 'json-object' },
  { key: 'max_completion_tokens', label: 'max_completion_tokens', group: 'limits', type: 'number', min: 1, step: 1, common: true },
  { key: 'n', label: 'n', group: 'limits', type: 'number', min: 1, step: 1 },
  { key: 'reasoning_effort', label: 'reasoning_effort', group: 'reasoning', type: 'select', options: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'], common: true },
  { key: 'response_format.type', label: 'response_format.type', group: 'structuredOutput', type: 'select', options: ['text', 'json_schema', 'json_object'] },
  { key: 'response_format.json_schema.name', label: 'response_format.json_schema.name', group: 'structuredOutput', type: 'string', showWhen: { key: 'response_format.type', value: 'json_schema' } },
  { key: 'response_format.json_schema.strict', label: 'response_format.json_schema.strict', group: 'structuredOutput', type: 'boolean', showWhen: { key: 'response_format.type', value: 'json_schema' } },
  { key: 'response_format.json_schema.schema', label: 'response_format.json_schema.schema', group: 'structuredOutput', type: 'json-object', showWhen: { key: 'response_format.type', value: 'json_schema' } },
  { key: 'tool_choice', label: 'tool_choice', group: 'tools', type: 'tool-choice-openai' },
  { key: 'parallel_tool_calls', label: 'parallel_tool_calls', group: 'tools', type: 'boolean' },
  { key: 'stream', label: 'stream', group: 'streamingCache', type: 'boolean', common: true },
  { key: 'stream_options.include_usage', label: 'stream_options.include_usage', group: 'streamingCache', type: 'boolean' },
  { key: 'stream_options.include_obfuscation', label: 'stream_options.include_obfuscation', group: 'streamingCache', type: 'boolean' },
  { key: 'prompt_cache_options.mode', label: 'prompt_cache_options.mode', group: 'streamingCache', type: 'select', options: ['implicit', 'explicit'] },
  { key: 'prompt_cache_options.ttl', label: 'prompt_cache_options.ttl', group: 'streamingCache', type: 'select', options: ['30m'] },
  { key: 'prompt_cache_key', label: 'prompt_cache_key', group: 'streamingCache', type: 'string', hiddenByDefault: true },
  { key: 'logprobs', label: 'logprobs', group: 'advanced', type: 'boolean' },
  { key: 'top_logprobs', label: 'top_logprobs', group: 'advanced', type: 'number', min: 0, max: 20, step: 1 },
  { key: 'prediction.type', label: 'prediction.type', group: 'advanced', type: 'select', options: ['content'] },
  { key: 'prediction.content', label: 'prediction.content', group: 'advanced', type: 'string' },
  { key: 'verbosity', label: 'verbosity', group: 'advanced', type: 'select', options: ['low', 'medium', 'high'] }
]

const openAiResponsesFields = [
  { key: 'temperature', label: 'temperature', group: 'sampling', type: 'number', min: 0, max: 2, step: 0.1, common: true },
  { key: 'top_p', label: 'top_p', group: 'sampling', type: 'number', min: 0, max: 1, step: 0.1 },
  { key: 'max_output_tokens', label: 'max_output_tokens', group: 'limits', type: 'number', min: 1, step: 1, common: true },
  { key: 'reasoning.effort', label: 'reasoning.effort', group: 'reasoning', type: 'select', options: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'], common: true },
  { key: 'reasoning.summary', label: 'reasoning.summary', group: 'reasoning', type: 'select', options: ['auto', 'concise', 'detailed'] },
  { key: 'reasoning.context', label: 'reasoning.context', group: 'reasoning', type: 'select', options: ['auto', 'current_turn', 'all_turns'] },
  { key: 'text.verbosity', label: 'text.verbosity', group: 'structuredOutput', type: 'select', options: ['low', 'medium', 'high'] },
  { key: 'text.format.type', label: 'text.format.type', group: 'structuredOutput', type: 'select', options: ['text', 'json_schema', 'json_object'] },
  { key: 'text.format.name', label: 'text.format.name', group: 'structuredOutput', type: 'string', showWhen: { key: 'text.format.type', value: 'json_schema' } },
  { key: 'text.format.strict', label: 'text.format.strict', group: 'structuredOutput', type: 'boolean', showWhen: { key: 'text.format.type', value: 'json_schema' } },
  { key: 'text.format.schema', label: 'text.format.schema', group: 'structuredOutput', type: 'json-object', showWhen: { key: 'text.format.type', value: 'json_schema' } },
  { key: 'tool_choice', label: 'tool_choice', group: 'tools', type: 'tool-choice-responses' },
  { key: 'parallel_tool_calls', label: 'parallel_tool_calls', group: 'tools', type: 'boolean' },
  { key: 'stream', label: 'stream', group: 'streamingCache', type: 'boolean', common: true },
  { key: 'stream_options.include_obfuscation', label: 'stream_options.include_obfuscation', group: 'streamingCache', type: 'boolean' },
  { key: 'prompt_cache_options.mode', label: 'prompt_cache_options.mode', group: 'streamingCache', type: 'select', options: ['implicit', 'explicit'] },
  { key: 'prompt_cache_options.ttl', label: 'prompt_cache_options.ttl', group: 'streamingCache', type: 'select', options: ['30m'] },
  { key: 'prompt_cache_key', label: 'prompt_cache_key', group: 'streamingCache', type: 'string', hiddenByDefault: true },
  { key: 'top_logprobs', label: 'top_logprobs', group: 'advanced', type: 'number', min: 0, max: 20, step: 1 },
  { key: 'truncation', label: 'truncation', group: 'advanced', type: 'select', options: ['auto', 'disabled'] }
]

const openAiCompatibleFields = [
  { key: 'thinking.type', label: 'thinking.type', group: 'reasoning', type: 'select', options: ['enabled', 'disabled'] },
  { key: 'output_config.effort', label: 'output_config.effort', group: 'reasoning', type: 'select', options: ['low', 'medium', 'high', 'xhigh', 'max'] },
  { key: 'output_config.format.type', label: 'output_config.format.type', group: 'structuredOutput', type: 'select', options: ['json_schema'] },
  { key: 'output_config.format.schema', label: 'output_config.format.schema', group: 'structuredOutput', type: 'json-object', showWhen: { key: 'output_config.format.type', value: 'json_schema' } }
]

const anthropicFields = [
  { key: 'temperature', label: 'temperature', group: 'sampling', type: 'number', min: 0, max: 1, step: 0.1, common: true },
  { key: 'top_p', label: 'top_p', group: 'sampling', type: 'number', min: 0, max: 1, step: 0.1 },
  { key: 'top_k', label: 'top_k', group: 'sampling', type: 'number', min: 1, step: 1 },
  { key: 'max_tokens', label: 'max_tokens', group: 'sampling', type: 'number', min: 1, step: 1, common: true },
  { key: 'stop_sequences', label: 'stop_sequences', group: 'limits', type: 'string-list' },
  { key: 'thinking.type', label: 'thinking.type', group: 'reasoning', type: 'select', options: ['enabled', 'disabled', 'adaptive'], common: true },
  { key: 'thinking.budget_tokens', label: 'thinking.budget_tokens', group: 'reasoning', type: 'number', min: 1, step: 1, common: true, showWhen: { key: 'thinking.type', values: ['enabled', 'adaptive'] } },
  { key: 'thinking.display', label: 'thinking.display', group: 'reasoning', type: 'select', options: ['summarized', 'omitted'], common: true, showWhen: { key: 'thinking.type', values: ['enabled', 'adaptive'] } },
  { key: 'output_config.effort', label: 'output_config.effort', group: 'reasoning', type: 'select', options: ['low', 'medium', 'high', 'xhigh', 'max'], common: true },
  { key: 'output_config.format.type', label: 'output_config.format.type', group: 'structuredOutput', type: 'select', options: ['json_schema'] },
  { key: 'output_config.format.schema', label: 'output_config.format.schema', group: 'structuredOutput', type: 'json-object', showWhen: { key: 'output_config.format.type', value: 'json_schema' } },
  { key: 'tool_choice.type', label: 'tool_choice.type', group: 'tools', type: 'select', options: ['auto', 'any', 'none', 'tool'] },
  { key: 'tool_choice.name', label: 'tool_choice.name', group: 'tools', type: 'string', showWhen: { key: 'tool_choice.type', value: 'tool' } },
  { key: 'tool_choice.disable_parallel_tool_use', label: 'tool_choice.disable_parallel_tool_use', group: 'tools', type: 'boolean' },
  { key: 'stream', label: 'stream', group: 'streamingCache', type: 'boolean', common: true },
  { key: 'cache_control.type', label: 'cache_control.type', group: 'streamingCache', type: 'select', options: ['ephemeral'] },
  { key: 'cache_control.ttl', label: 'cache_control.ttl', group: 'streamingCache', type: 'select', options: ['5m', '1h'] }
]

export function clonePlainObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? JSON.parse(JSON.stringify(value))
    : {}
}

export function pruneEmptyParams(value) {
  if (Array.isArray(value)) {
    const items = value
      .map(pruneEmptyParams)
      .filter((item) => item !== undefined)
    return items.length ? items : undefined
  }
  if (value && typeof value === 'object') {
    const entries = Object.entries(value)
      .map(([key, child]) => [key, pruneEmptyParams(child)])
      .filter(([, child]) => child !== undefined)
    return entries.length ? Object.fromEntries(entries) : undefined
  }
  if (value === '' || value === null || value === undefined) return undefined
  return value
}

export function modelStreamValue(modelParams) {
  return typeof modelParams?.stream === 'boolean' ? modelParams.stream : null
}

export function modelTokenValue(providerKind, apiType, modelParams) {
  if (providerKind === 'anthropic') return modelParams?.max_tokens ?? null
  if (apiType === 'responses') return modelParams?.max_output_tokens ?? null
  return modelParams?.max_completion_tokens ?? null
}
