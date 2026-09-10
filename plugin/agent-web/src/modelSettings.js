export function assignReactive(target, source) {
  Object.keys(target).forEach((key) => delete target[key])
  Object.assign(target, source)
}

export function optionValue(value) {
  if (value === undefined || value === null || value === '') return null
  return value
}

export function parseJsonObject(text) {
  const value = String(text || '').trim()
  if (!value) return null
  const parsed = JSON.parse(value)
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error('additional_params 必须是 JSON object')
  }
  return parsed
}
