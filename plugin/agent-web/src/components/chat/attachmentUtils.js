import { invoke, api } from '../../bridge/jcefBridge'

const previewCache = new Map()

function cacheKey(sessionId, attachment) {
  return `${sessionId}:${attachment?.id || attachment?.path || ''}`
}

export function attachmentUrl(attachment, sessionId) {
  if (attachment?.url) return attachment.url
  if (attachment?.previewUrl) return attachment.previewUrl
  return ''
}

export async function loadAttachmentPreview(attachment, sessionId) {
  if (!attachment) return ''
  const existing = attachmentUrl(attachment, sessionId)
  if (existing) return existing
  const key = cacheKey(sessionId, attachment)
  if (previewCache.has(key)) return previewCache.get(key)
  const data = attachment.path
    ? await api('attachment.preview', { path: attachment.path })
    : (!sessionId || !attachment.id ? null : await api('attachment.preview', { sessionId: String(sessionId), id: String(attachment.id) }))
  if (!data) return ''
  const url = data?.url || ''
  if (url) previewCache.set(key, url)
  return url
}

export function isImageAttachment(attachment) {
  const type = String(attachment?.content_type || attachment?.mimeType || '').toLowerCase()
  if (type.startsWith('image/')) return true
  if (String(attachment?.kind || '').toLowerCase() === 'image') return true
  const name = String(attachment?.file_name || attachment?.name || attachment?.path || '').toLowerCase()
  return /\.(png|jpe?g|gif|webp|bmp|svg)$/.test(name)
}

export function formatAttachmentSize(size) {
  if (!Number.isFinite(Number(size))) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = Number(size)
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

export function attachmentKind(attachment, fallback = '文件') {
  const type = String(attachment?.content_type || attachment?.mimeType || '').toLowerCase()
  if (type.includes('/')) return type.split('/').pop().toUpperCase()
  return type || fallback
}

export function isDesktopApp() {
  return true
}

export function invokeErrorMessage(error) {
  if (typeof error === 'string' && error.trim()) return error
  if (error?.message) return String(error.message)
  return String(error)
}

export async function openDesktopAttachment(kind, sessionId, attachmentId) {
  if (!sessionId || !attachmentId) throw new Error('附件缺少会话或附件编号')
  await invoke('attachment.openById', { sessionId, id: String(attachmentId) })
}
