const OPENAI_LEVELS = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']
const OUTPUT_EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max']

const LEVEL_LABELS = {
  none: '关闭',
  minimal: '极低',
  low: '低',
  medium: '中',
  high: '高',
  xhigh: '超高',
  max: '最大',
}

export function reasoningLabel(value) {
  return LEVEL_LABELS[value] || value || '继承模型'
}

export function reasoningOptionsFor(provider, model) {
  const usesOutputEffort = provider?.kind === 'anthropic' || model?.model_params?.output_config?.effort !== undefined
  const values = usesOutputEffort ? OUTPUT_EFFORT_LEVELS : OPENAI_LEVELS
  return values.map((value) => ({ value, label: reasoningLabel(value) }))
}

export function modelReasoningLevel(model) {
  return model?.model_params?.output_config?.effort
    ?? model?.model_params?.reasoning?.effort
    ?? model?.model_params?.reasoning_effort
    ?? null
}

export function modelThinkingConfig(model) {
  const thinking = model?.model_params?.thinking
  if (!thinking || typeof thinking !== 'object') return { type: 'disabled' }
  return {
    type: thinking.type || 'disabled',
    budget_tokens: thinking.budget_tokens ?? 1024,
    display: thinking.display || 'summarized',
  }
}

export function thinkingConfigForType(type, model, current) {
  if (!type) return null
  if (type === 'disabled') return { type: 'disabled' }
  const base = current && typeof current === 'object' ? current : modelThinkingConfig(model)
  return {
    type,
    budget_tokens: Number(base?.budget_tokens) >= 1024 ? Number(base.budget_tokens) : 1024,
    display: ['summarized', 'omitted'].includes(base?.display) ? base.display : 'summarized',
  }
}

export function validateReasoningOverride(provider, config) {
  if (provider?.kind !== 'anthropic' || !config || config.type === 'disabled') return
  if (!Number.isFinite(Number(config.budget_tokens)) || Number(config.budget_tokens) < 1024) {
    throw new Error('thinking 启用时 budget_tokens 必须 >= 1024')
  }
  if (!['summarized', 'omitted'].includes(config.display)) {
    throw new Error('thinking 启用时必须选择 summarized 或 omitted')
  }
}
