export function isResponsesApi(api) {
  return api === 'responses'
}

export function isCompletionsApi(api) {
  return !isResponsesApi(api)
}

export function effectiveModelApi(modelApi, providerApi = 'completions') {
  return modelApi || providerApi || 'completions'
}

export function supportsSamplingParams(api) {
  return isCompletionsApi(api) || isResponsesApi(api)
}

export function supportsStreamParam(api) {
  return isCompletionsApi(api) || isResponsesApi(api)
}

export function supportsMaxTokensParam(api) {
  return isCompletionsApi(api) || isResponsesApi(api)
}

export const DEFAULT_ANTHROPIC_MAX_TOKENS = 4096

export function maxTokensOrAnthropicDefault(value, providerKind = '') {
  if (value !== undefined && value !== null && value !== '') return value
  return providerKind === 'anthropic' ? DEFAULT_ANTHROPIC_MAX_TOKENS : value
}

export function supportsThinkingBudgetParam(api) {
  return isCompletionsApi(api)
}

export function supportsParallelToolCallsParam(api) {
  return isCompletionsApi(api) || isResponsesApi(api)
}

export function supportsPromptCacheKeyParam(api) {
  return isCompletionsApi(api) || isResponsesApi(api)
}

export function supportsReasoningParams(api) {
  return true
}

export function advancedModelParamDefaults() {
  return {
    parallel_tool_calls: false,
    anthropic_prompt_cache: false,
    anthropic_prompt_cache_ttl: '',
    anthropic_thinking_type: '',
    anthropic_thinking_display: '',
    anthropic_thinking_budget_tokens: null,
    prompt_cache_key: ''
  }
}

export function hydrateAdvancedModelParams(additionalParams = null) {
  const params = additionalParams && typeof additionalParams === 'object' && !Array.isArray(additionalParams)
    ? additionalParams
    : {}
  return {
    parallel_tool_calls: params.parallel_tool_calls === true,
    anthropic_prompt_cache: params.cache_control?.type === 'ephemeral',
    anthropic_prompt_cache_ttl: ['5m', '1h'].includes(params.cache_control?.ttl)
      ? params.cache_control.ttl
      : '',
    anthropic_thinking_type: ['enabled', 'disabled', 'adaptive'].includes(params.thinking?.type)
      ? params.thinking.type
      : '',
    anthropic_thinking_display: ['summarized', 'omitted'].includes(params.thinking?.display)
      ? params.thinking.display
      : '',
    anthropic_thinking_budget_tokens: Number.isFinite(params.thinking?.budget_tokens)
      ? params.thinking.budget_tokens
      : null,
    prompt_cache_key: typeof params.prompt_cache_key === 'string' ? params.prompt_cache_key : ''
  }
}

export function visibleAdditionalParams(additionalParams = null, options = {}) {
  if (!additionalParams || typeof additionalParams !== 'object' || Array.isArray(additionalParams)) return null
  const next = { ...additionalParams }
  if (options.hideParallelToolCalls !== false) delete next.parallel_tool_calls
  if (options.hideAnthropicPromptCache !== false) delete next.cache_control
  if (options.hidePromptCacheKey !== false) delete next.prompt_cache_key
  if (options.hidePromptCacheOptions !== false) delete next.prompt_cache_options
  return Object.keys(next).length ? next : null
}

export function visibleAdditionalParamsText(additionalParams = null, options = {}) {
  const visible = visibleAdditionalParams(additionalParams, options)
  return visible ? JSON.stringify(visible, null, 2) : ''
}

export function mergeAdvancedModelParams(additionalParams, advancedParams = {}, api = 'completions', options = {}) {
  const next = additionalParams && typeof additionalParams === 'object' && !Array.isArray(additionalParams)
    ? { ...additionalParams }
    : {}
  const includeParallelToolCalls = options.includeParallelToolCalls ?? supportsParallelToolCallsParam(api)
  const includePromptCacheKey = options.includePromptCacheKey ?? supportsPromptCacheKeyParam(api)
  const includeAnthropicPromptCache = options.includeAnthropicPromptCache === true
  const includeAnthropicThinking = options.includeAnthropicThinking === true
  const preservePromptCacheKey = options.preservePromptCacheKey === true

  if (includeParallelToolCalls) {
    next.parallel_tool_calls = advancedParams.parallel_tool_calls === true
  } else {
    delete next.parallel_tool_calls
  }

  const promptCacheKey = String(advancedParams.prompt_cache_key || '').trim()
  if (includePromptCacheKey && promptCacheKey) {
    next.prompt_cache_key = promptCacheKey
  } else if (!preservePromptCacheKey) {
    delete next.prompt_cache_key
  }

  if (includeAnthropicPromptCache) {
    if (advancedParams.anthropic_prompt_cache === true) {
      const ttl = ['5m', '1h'].includes(advancedParams.anthropic_prompt_cache_ttl)
        ? advancedParams.anthropic_prompt_cache_ttl
        : ''
      next.cache_control = ttl ? { type: 'ephemeral', ttl } : { type: 'ephemeral' }
    } else {
      delete next.cache_control
    }
  }

  if (includeAnthropicThinking) {
    const type = ['enabled', 'disabled', 'adaptive'].includes(advancedParams.anthropic_thinking_type)
      ? advancedParams.anthropic_thinking_type
      : (advancedParams.reasoning_enabled || advancedParams.thinking_enabled ? 'enabled' : 'disabled')
    const thinking = { type }
    if (type === 'enabled') {
      const budget = Number(advancedParams.thinking_budget_tokens ?? advancedParams.anthropic_thinking_budget_tokens)
      if (Number.isFinite(budget) && budget > 0) thinking.budget_tokens = budget
    }
    if (['enabled', 'adaptive'].includes(type)) {
      const display = String(advancedParams.anthropic_thinking_display || '').trim()
      if (['summarized', 'omitted'].includes(display)) thinking.display = display
    }
    next.thinking = thinking
  }

  return Object.keys(next).length ? next : null
}

export function promptCacheKeyFor({
  api = 'responses',
  scope = 'runtime',
  providerId = null,
  modelId = null,
  environmentId = null
} = {}) {
  if (!providerId || !modelId) return ''
  const env = environmentId || 'none'
  const apiPrefix = isResponsesApi(api) ? 'responses' : 'completions'
  const prefix = scope === 'distill'
    ? `awake-claw:${apiPrefix}:distill`
    : `awake-claw:${apiPrefix}`
  return `${prefix}:provider:${providerId}:model:${modelId}:env:${env}`
}

export function cleanModelParamsForApi(params, api) {
  const next = { ...params }
  if (isResponsesApi(api)) {
    next.stream = params.stream ?? true
    next.temperature = params.temperature ?? null
    next.top_p = params.top_p ?? null
    next.top_k = null
    next.max_tokens = params.max_tokens ?? null
    next.thinking_budget_tokens = null
  } else {
    next.stream = params.stream ?? true
  }
  return next
}
