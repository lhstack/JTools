<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, ChatLineRound, InfoFilled, Paperclip, RefreshRight, Tools, WarningFilled } from '@element-plus/icons-vue'
import MarkdownContent from './MarkdownContent.vue'
import { useI18n } from '../../composables/useI18n.js'
import { attachmentUrl as defaultAttachmentUrl, invokeErrorMessage, isDesktopApp, isImageAttachment, loadAttachmentPreview, openDesktopAttachment } from './attachmentUtils.js'

const { t } = useI18n()

const props = defineProps({
  events: { type: Array, default: () => [] },
  sessionId: { type: [Number, String], required: true },
  messageTasks: { type: Array, default: () => [] },
  attachmentUrl: { type: Function, default: null },
  attachmentKind: { type: String, default: 'chat' },
  loadEventDetail: { type: Function, default: null }
})
const emit = defineEmits(['delete-turn', 'preview-attachment', 'event-loaded'])

const rawMessageDialogVisible = ref(false)
const rawMessageTitle = ref('')
const rawMessageContent = ref('')
const rawContentPre = ref(null)
const copyRawLabel = ref('')
const attachmentPreviewVisible = ref(false)
const attachmentPreview = ref(null)
const previewUrls = reactive({})

const expandedMessageEventIds = ref(new Set())
const loadingMessageEventIds = ref(new Set())

function formatEventTime(value) {
  if (!value) return ''
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (item) => String(item).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function isUserFacingEvent(event) {
  return event?.event_type === 'user_message' || event?.event_type === 'append_message'
}

const conversationTurns = computed(() => {
  const turns = new Map()
  for (const event of props.events || []) {
    if (event.event_type === 'model_reply' && event.event_id === 'final') continue
    if (!turns.has(event.turn_id)) turns.set(event.turn_id, { turnId: event.turn_id, events: [] })
    const turn = turns.get(event.turn_id)
    if (event.parent_event_id) continue
    turn.events.push(event)
  }
  return [...turns.values()]
    .map((turn) => {
      const events = [...turn.events].sort((left, right) => Number(left.id) - Number(right.id))
      const items = []
      for (const event of events) {
        if (isUserFacingEvent(event)) {
          items.push({ kind: 'user', event })
          continue
        }
        const last = items[items.length - 1]
        if (last?.kind === 'timeline') last.events.push(event)
        else items.push({ kind: 'timeline', events: [event] })
      }
      return { turnId: turn.turnId, items }
    })
    .sort((left, right) => {
      const leftId = Math.min(...left.items.flatMap((item) => item.kind === 'user' ? [Number(item.event.id)] : item.events.map((event) => Number(event.id))))
      const rightId = Math.min(...right.items.flatMap((item) => item.kind === 'user' ? [Number(item.event.id)] : item.events.map((event) => Number(event.id))))
      return leftId - rightId
    })
})

function toggleMessageEvent(eventId) {
  const next = new Set(expandedMessageEventIds.value)
  if (next.has(eventId)) next.delete(eventId)
  else next.add(eventId)
  expandedMessageEventIds.value = next
}

function isMessageEventExpanded(eventId) {
  return expandedMessageEventIds.value.has(eventId)
}

function setMessageEventExpanded(eventId, expanded) {
  const next = new Set(expandedMessageEventIds.value)
  if (expanded) next.add(eventId)
  else next.delete(eventId)
  expandedMessageEventIds.value = next
  if (!expanded || !props.loadEventDetail || !Number.isFinite(Number(eventId))) return
  const event = (props.events || []).find((item) => Number(item.id) === Number(eventId))
  if (event?.context || loadingMessageEventIds.value.has(eventId)) return
  loadingMessageEventIds.value = new Set([...loadingMessageEventIds.value, eventId])
  Promise.resolve(props.loadEventDetail(eventId))
    .then((detail) => {
      if (detail) emit('event-loaded', detail)
    })
    .catch(() => {})
    .finally(() => {
      const loading = new Set(loadingMessageEventIds.value)
      loading.delete(eventId)
      loadingMessageEventIds.value = loading
    })
}

function eventContext(event) {
  return event?.context && typeof event.context === 'object' ? event.context : {}
}

function eventPayload(event) {
  const context = eventContext(event)
  return context.message && typeof context.message === 'object' ? context.message : context
}

function systemDeliveryType(event) {
  const concreteType = [
    'schedule_delivery',
    'workflow_delivery',
    'workflow_node_delivery',
    'distillation_notice',
    'compaction_notice',
    'cli_agent_delivery',
    'cli_chat_delivery',
    'wechat_authorization'
  ].includes(event?.event_type)
    ? event.event_type
    : null
  if (concreteType) return concreteType

  const extra = eventContext(event)?.extra
  if (!extra || typeof extra !== 'object' || extra.delivery_receiver !== 'user') return null
  return [
    'schedule_delivery',
    'workflow_delivery',
    'workflow_node_delivery',
    'distillation',
    'distillation_notice',
    'compaction_notice',
    'context_compaction',
    'cli_agent_delivery',
    'cli_chat_delivery',
    'wechat_authorization'
  ].includes(extra.type)
    ? extra.type
    : null
}

function isSystemDeliveryEvent(event) {
  return Boolean(systemDeliveryType(event))
}

function systemDeliveryLabel(event) {
  if (systemDeliveryFailed(event) && ['compaction_notice', 'context_compaction'].includes(systemDeliveryType(event))) {
    return '上下文压缩失败'
  }
  const labels = {
    schedule_delivery: t('chat.timeline.schedule'),
    workflow_delivery: t('chat.timeline.workflow'),
    workflow_node_delivery: t('chat.timeline.workflowNode'),
    distillation: t('chat.timeline.distill'),
    distillation_notice: t('chat.timeline.distill'),
    compaction_notice: t('chat.timeline.compact'),
    context_compaction: t('chat.timeline.compact'),
    cli_agent_delivery: t('chat.timeline.cliAgent'),
    cli_chat_delivery: t('chat.timeline.cliChat'),
    wechat_authorization: t('chat.timeline.wechatAuth')
  }
  return labels[systemDeliveryType(event)] || t('chat.timeline.notice')
}

function systemDeliveryFailed(event) {
  const extra = eventContext(event)?.extra
  return extra?.result === 'failed' || event?.status === 'failed'
}

function systemDeliveryText(event) {
  const payload = eventPayload(event)
  const extra = eventContext(event)?.extra
  const value = payload.content || payload.summary || payload.error || extra?.error || event?.summary || ''
  return String(value)
}

function eventText(event) {
  const payload = eventPayload(event)
  const values = [
    payload.content,
    payload.text,
    payload.response,
    payload.reasoning,
    payload.reasoning_content,
    payload.thinking,
    event?.summary
  ]
  const value = values.find((item) => typeof item === 'string' && item.trim())
  if (value) return value
  const list = values.find((item) => Array.isArray(item) && item.length)
  if (list) return list.join('')
  return t('chat.timeline.empty')
}

function eventLabel(event) {
  const labels = {
    user_message: t('chat.timeline.user'),
    append_message: t('chat.timeline.append'),
    model_reasoning: t('chat.timeline.reasoning'),
    model_reply: t('chat.timeline.reply'),
    tool_call: t('chat.timeline.tool'),
    model_reply_cancelled: t('chat.timeline.cancelled'),
    task_failed: t('chat.timeline.failed'),
    model_retry: t('chat.timeline.retry'),
    subagent: t('chat.timeline.subagent'),
    cli_agent_delivery: t('chat.timeline.cliAgent'),
    cli_chat_delivery: t('chat.timeline.cliChat'),
    wechat_authorization: t('chat.timeline.wechatAuth')
  }
  return labels[event?.event_type] || event?.event_type || t('chat.timeline.event')
}

function retryCount(event) {
  const count = Number(eventContext(event).retry_count)
  if (Number.isFinite(count) && count > 0) return count
  const summary = String(event?.summary || '')
  const matched = summary.match(/×(\d+)/)
  return matched ? Number(matched[1]) : 1
}

function retrySummary(event) {
  return `${t('chat.timeline.retry')} ×${retryCount(event)}`
}

function childEvents(parentId) {
  return (props.events || [])
    .filter((event) => Number(event.parent_event_id) === Number(parentId))
    .sort((left, right) => Number(left.id) - Number(right.id))
}

function compactTitle(value, fallback = '') {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  if (!text) return fallback
  return [...text].length > 48 ? `${[...text].slice(0, 48).join('')}…` : text
}

function subagentTitle(event) {
  const context = eventContext(event)
  return compactTitle(context.title || context.task || event?.summary)
}

function subagentName(event) {
  const context = eventContext(event)
  const name = context.agent_name || context.agent?.name || 'subagent'
  const title = subagentTitle(event)
  return title ? `${name} · ${title}` : name
}

function toolSummaryName(event) {
  const summary = typeof event?.summary === 'string' ? event.summary.trim() : ''
  if (!summary) return ''
  return summary.replace(/\s+(?:running|completed|failed|cancelled)$/i, '').trim() || summary
}

function toolName(event) {
  const context = eventContext(event)
  const name = context.name || context.tool_name || toolSummaryName(event) || 'tool'
  if (name !== 'subagent_run') return name
  const result = context.result && typeof context.result === 'object' ? context.result : {}
  const agent = result.agent?.name || result.agent_name
  const title = compactTitle(result.title || result.task || context.arguments?.title || context.arguments?.task)
  const labeled = agent ? `${name} · ${agent}` : name
  return title ? `${labeled} · ${title}` : labeled
}

function toolStatus(event) {
  return event?.status
}

function eventSummary(event) {
  if (event?.event_type === 'model_retry') return retrySummary(event)
  if (event?.event_type === 'tool_call') return toolName(event)
  if (event?.event_type === 'subagent') return subagentName(event)
  const text = eventText(event)
  if (text === t('chat.timeline.empty')) return event?.summary || text
  const chars = [...text]
  return chars.length > 160 ? chars.slice(-160).join('') : text
}

function retryError(event) {
  const context = eventContext(event)
  return context.error || context.message || eventSummary(event)
}

function eventAttachments(event) {
  const context = eventContext(event)
  const payload = eventPayload(event)
  const items = payload.attachment_items || context.attachment_items || payload.attachments || context.attachments || []
  return Array.isArray(items) ? items.filter((item) => item && typeof item === 'object' && item.id) : []
}

function eventAttachmentUrl(event, attachment) {
  const sessionId = props.sessionId || event?.session_id
  if (!sessionId || !attachment?.id) return ''
  return props.attachmentUrl
    ? props.attachmentUrl(attachment, sessionId)
    : defaultAttachmentUrl(attachment, sessionId)
}

function previewKey(attachment) {
  return String(attachment?.id || attachment?.path || '')
}

function previewSrc(attachment, event) {
  const key = previewKey(attachment)
  if (!key) return ''
  if (Object.prototype.hasOwnProperty.call(previewUrls, key)) return previewUrls[key]
  const existing = eventAttachmentUrl(event, attachment)
  if (existing) {
    previewUrls[key] = existing
    return existing
  }
  previewUrls[key] = ''
  const sessionId = props.sessionId || event?.session_id
  loadAttachmentPreview(attachment, sessionId).then((url) => {
    previewUrls[key] = url || ''
  }).catch(() => {
    previewUrls[key] = ''
  })
  return ''
}

function desktopAttachmentKind(event) {
  return event?.event_type === 'attachment' ? 'chat' : props.attachmentKind
}

async function previewEventAttachment(event, attachment) {
  if (!isImageAttachment(attachment)) return
  const url = previewSrc(attachment, event) || await loadAttachmentPreview(attachment, props.sessionId || event?.session_id)
  if (!url) return
  attachmentPreview.value = { ...attachment, url }
  attachmentPreviewVisible.value = true
  emit('preview-attachment', { event, attachment, url })
}

async function openEventAttachment(event, attachment, clickEvent) {
  if (isImageAttachment(attachment)) {
    await previewEventAttachment(event, attachment)
    return
  }
  if (!isDesktopApp()) return
  clickEvent?.preventDefault()
  try {
    await openDesktopAttachment(
      desktopAttachmentKind(event),
      props.sessionId || event?.session_id,
      attachment.id
    )
  } catch (error) {
    ElMessage.error(invokeErrorMessage(error))
  }
}

function eventUsage(event) {
  const context = eventContext(event)
  const payload = eventPayload(event)
  return payload.usage || context.usage || null
}

function usageRows(usage, prefix = '') {
  if (!usage || typeof usage !== 'object') return []
  return Object.entries(usage).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    if (value === null || value === undefined) return []
    if (Array.isArray(value)) return value.flatMap((item, index) => usageRows({ [index]: item }, path))
    if (typeof value === 'object') return usageRows(value, path)
    return [{ key: path, value: typeof value === 'number' && Number.isInteger(value) ? value : String(value) }]
  })
}

function numericUsage(usage, ...keys) {
  for (const key of keys) {
    const value = Number(usage?.[key])
    if (Number.isFinite(value) && value > 0) return value
  }
  return 0
}

function mergeUsage(target, source) {
  for (const [key, value] of Object.entries(source || {})) {
    if (typeof value === 'number' && Number.isFinite(value)) target[key] = (Number(target[key]) || 0) + value
    else if (value && typeof value === 'object' && !Array.isArray(value)) {
      if (!target[key] || typeof target[key] !== 'object') target[key] = {}
      mergeUsage(target[key], value)
    } else if (target[key] === undefined) target[key] = value
  }
}

function totalTokenUsage(usage) {
  const explicit = numericUsage(usage, 'total_tokens')
  if (explicit) return explicit
  return numericUsage(usage, 'input_tokens', 'prompt_tokens')
    + numericUsage(usage, 'output_tokens', 'completion_tokens')
    + numericUsage(usage, 'cached_input_tokens', 'cache_read_input_tokens')
    + numericUsage(usage, 'cache_creation_input_tokens')
    + numericUsage(usage, 'estimated_tokens')
}

function formatTokenUsage(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return '0 tokens'
  const unit = number >= 1_000_000 ? { divisor: 1_000_000, suffix: 'm' }
    : number >= 1_000 ? { divisor: 1_000, suffix: 'k' }
      : null
  if (!unit) return `${Math.round(number)} tokens`
  const scaled = number / unit.divisor
  return `${(scaled >= 10 ? scaled.toFixed(0) : scaled.toFixed(1)).replace(/\.0$/, '')}${unit.suffix}`
}

function turnEvents(turn) {
  return (turn?.items || []).flatMap((item) => item.kind === 'user' ? [item.event] : item.events)
}

function turnTimelineEvents(turn) {
  return (turn?.items || []).flatMap((item) => item.kind === 'timeline' ? item.events : [])
}

function turnLastEvent(turn) {
  const events = turnEvents(turn)
  return events[events.length - 1] || null
}

function turnTask(turn) {
  return (props.messageTasks || []).find((task) => task.turn_id === turn?.turnId) || null
}

function turnStatus(turn) {
  const task = turnTask(turn)
  if (task?.status === 'processing' || task?.status === 'pending') return 'running'
  const events = turnTimelineEvents(turn)
  if (task?.status === 'failed' || events.some((event) => event.event_type === 'task_failed')) return 'failed'
  if (task?.status === 'cancelled' || events.some((event) => event.event_type === 'model_reply_cancelled')) return 'cancelled'
  return 'completed'
}

function turnStatusSymbol(turn) {
  const status = turnStatus(turn)
  if (status === 'running') return '↻'
  if (status === 'completed') return '✓'
  return '×'
}

function countsTowardTokenBudget(event) {
  return event?.event_type !== 'model_retry' && event?.event_type !== 'task_failed'
}

function turnUsage(turn) {
  const events = (turnTimelineEvents(turn)).filter(countsTowardTokenBudget)
  const usageEvents = events
    .map((event) => eventUsage(event))
    .filter((usage) => usage && typeof usage === 'object' && usageRows(usage).length)
  if (usageEvents.length) {
    const usage = {}
    for (const source of usageEvents) mergeUsage(usage, source)
    return usage
  }
  const estimated = events.reduce((total, event) => {
    const context = eventContext(event)
    const stored = Number(context.estimated_tokens)
    if (Number.isFinite(stored) && stored > 0) return total + stored
    return total + [...eventText(event)].length * 2
  }, 0)
  return { estimated_tokens: estimated, estimated: true }
}

async function copyModelReply(event) {
  const text = modelReplyText(event)
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      if (!document.execCommand('copy')) throw new Error('浏览器拒绝复制操作')
      textarea.remove()
    }
    ElMessage.success(t('chat.copiedReply'))
  } catch (err) {
    ElMessage.error(t('common.copyFailed', { reason: err.message || err }))
  }
}

function openRawMessage(event) {
  rawMessageTitle.value = eventLabel(event)
  rawMessageContent.value = modelReplyText(event)
  rawMessageDialogVisible.value = true
  copyRawLabel.value = t('common.copy')
}

async function copyRawContent() {
  try {
    await navigator.clipboard.writeText(rawMessageContent.value)
    copyRawLabel.value = t('common.copied')
  } catch (err) {
    ElMessage.error(t('common.copyFailed', { reason: err.message || err }))
  }
}

function handleRawDialogKeydown(event) {
  if (!(event.metaKey || event.ctrlKey) || String(event.key).toLowerCase() !== 'a') return
  event.preventDefault()
  const pre = rawContentPre.value
  if (!pre) return
  const range = document.createRange()
  range.selectNodeContents(pre)
  const selection = window.getSelection()
  selection.removeAllRanges()
  selection.addRange(range)
}

function deleteTurn(turn) {
  emit('delete-turn', turn)
}

function eventDetail(event) {
  if (event?.context) return JSON.stringify(event.context, null, 2)
  return t('chat.timeline.detailPending')
}

function eventTextValue(event) {
  return eventText(event) === t('chat.timeline.empty') ? '' : eventText(event)
}

function eventValueText(value) {
  if (value == null) return t('chat.timeline.noResult')
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

function reasoningText(event) {
  const payload = eventPayload(event)
  const value = payload.reasoning
    ?? payload.reasoning_content
    ?? payload.thinking
    ?? payload.text
  if (Array.isArray(value)) return value.join('')
  return typeof value === 'string' ? value : eventTextValue(event)
}

function modelReplyText(event) {
  const payload = eventPayload(event)
  const value = payload.response ?? payload.text ?? payload.content
  if (Array.isArray(value)) return value.join('')
  return typeof value === 'string' ? value : eventTextValue(event)
}

function toolArguments(event) {
  const context = eventContext(event)
  return context.arguments ?? context.args ?? context.input ?? {}
}

function toolResult(event) {
  const context = eventContext(event)
  return context.result ?? context.output ?? null
}
</script>

<template>
<section class="conversation-timeline">
  <article v-for="turn in conversationTurns" :key="turn.turnId" class="conversation-turn">
    <template v-for="(item, itemIndex) in turn.items" :key="item.kind === 'user' ? item.event.id : `timeline-${itemIndex}`">
    <div v-if="item.kind === 'user'" :data-event-id="item.event.id" class="user-event-row">
      <div class="user-message">
        <div v-if="eventTextValue(item.event)" class="user-message-text">{{ eventTextValue(item.event) }}</div>
        <div v-if="eventAttachments(item.event).length" class="user-event-attachments">
          <template v-for="attachment in eventAttachments(item.event)" :key="attachment.id">
            <button
                v-if="isImageAttachment(attachment)"
                type="button"
                class="user-attachment"
                @click="previewEventAttachment(item.event, attachment)"
            >
              <img
                  v-if="previewSrc(attachment, item.event)"
                  :src="previewSrc(attachment, item.event)"
                  :alt="attachment.file_name"
                  loading="lazy"
              />
              <span v-else class="user-attachment-placeholder">{{ attachment.file_name || '图片' }}</span>
            </button>
            <a
                v-else
                class="user-attachment user-attachment-link"
                :href="previewSrc(attachment, item.event)"
                target="_blank"
                rel="noopener noreferrer"
                :title="attachment.file_name"
                @click="openEventAttachment(item.event, attachment, $event)"
            >
              <span>{{ attachment.file_name }}</span>
            </a>
          </template>
        </div>
      </div>
      <time class="user-time">{{ formatEventTime(item.event.updated_at || item.event.created_at) }}</time>
    </div>

    <div v-else class="assistant-timeline">
      <div class="timeline-track">
        <div v-for="event in item.events" :key="event.id" :data-event-id="event.id" class="timeline-event-row" :class="`event-kind-${event.event_type}`">

          <details
              v-if="event.event_type === 'model_reasoning'"
              class="reasoning-event"
              :open="isMessageEventExpanded(event.id)"
              @toggle="setMessageEventExpanded(event.id, $event.target.open)"
          >
            <summary>
              <span class="timeline-icon reasoning" aria-hidden="true"><el-icon><ChatLineRound /></el-icon></span>
              <span>{{ eventSummary(event) }}</span>
            </summary>
            <div class="reasoning-detail">{{ reasoningText(event) }}</div>
          </details>

          <details
              v-else-if="event.event_type === 'tool_call'"
              class="tool-event"
              :class="`status-${toolStatus(event)}`"
              :open="isMessageEventExpanded(event.id)"
              @toggle="setMessageEventExpanded(event.id, $event.target.open)"
          >
            <summary>
              <span class="timeline-icon tool" :class="`status-${toolStatus(event)}`" aria-hidden="true"><el-icon><Tools /></el-icon></span>
              <span>{{ eventSummary(event) }}</span>
            </summary>
            <div class="tool-event-detail">
              <div class="tool-value-pane">
                <strong>{{ t('chat.toolArgs') }}</strong>
                <pre>{{ eventValueText(toolArguments(event)) }}</pre>
              </div>
              <div class="tool-value-pane">
                <strong>{{ t('chat.toolResult') }}</strong>
                <pre>{{ eventValueText(toolResult(event)) }}</pre>
              </div>
            </div>
          </details>

          <details
              v-else-if="event.event_type === 'subagent'"
              class="subagent-event"
              :class="`status-${event.status}`"
              :open="isMessageEventExpanded(event.id)"
              @toggle="setMessageEventExpanded(event.id, $event.target.open)"
          >
            <summary>
              <span class="timeline-icon subagent" aria-hidden="true"><el-icon><ChatDotRound /></el-icon></span>
              <span>{{ eventSummary(event) }}</span>
            </summary>
            <div class="nested-timeline">
              <div
                  v-for="child in childEvents(event.id)"
                  :key="child.id"
                  :data-event-id="child.id"
                  class="timeline-event-row nested-event-row"
                  :class="`event-kind-${child.event_type}`"
              >
                <details
                    v-if="child.event_type === 'model_reasoning'"
                    class="reasoning-event"
                    :open="isMessageEventExpanded(child.id)"
                    @toggle="setMessageEventExpanded(child.id, $event.target.open)"
                >
                  <summary>
                    <span class="timeline-icon reasoning" aria-hidden="true"><el-icon><ChatLineRound /></el-icon></span>
                    <span>{{ eventSummary(child) }}</span>
                  </summary>
                  <div class="reasoning-detail">{{ reasoningText(child) }}</div>
                </details>
                <details
                    v-else-if="child.event_type === 'tool_call'"
                    class="tool-event"
                    :class="`status-${toolStatus(child)}`"
                    :open="isMessageEventExpanded(child.id)"
                    @toggle="setMessageEventExpanded(child.id, $event.target.open)"
                >
                  <summary>
                    <span class="timeline-icon tool" :class="`status-${toolStatus(child)}`" aria-hidden="true"><el-icon><Tools /></el-icon></span>
                    <span>{{ eventSummary(child) }}</span>
                  </summary>
                  <div class="tool-event-detail">
                    <div class="tool-value-pane">
                      <strong>{{ t('chat.toolArgs') }}</strong>
                      <pre>{{ eventValueText(toolArguments(child)) }}</pre>
                    </div>
                    <div class="tool-value-pane">
                      <strong>{{ t('chat.toolResult') }}</strong>
                      <pre>{{ eventValueText(toolResult(child)) }}</pre>
                    </div>
                  </div>
                </details>
                <details
                    v-else-if="child.event_type === 'model_retry'"
                    class="retry-event"
                    :open="isMessageEventExpanded(child.id)"
                    @toggle="setMessageEventExpanded(child.id, $event.target.open)"
                >
                  <summary>
                    <span class="timeline-icon retry" aria-hidden="true"><el-icon><RefreshRight /></el-icon></span>
                    <span>{{ eventSummary(child) }}</span>
                  </summary>
                  <pre class="retry-error-detail">{{ retryError(child) }}</pre>
                </details>
                <div v-else-if="child.event_type === 'model_reply'" class="model-reply-event">
                  <div class="markdown-event-row">
                    <span class="markdown-timeline-marker" aria-hidden="true"><el-icon><ChatDotRound /></el-icon></span>
                    <div class="model-reply-body">
                      <MarkdownContent :content="modelReplyText(child)" />
                    </div>
                  </div>
                </div>
                <details
                    v-else
                    class="system-event"
                    :class="{ failed: child.status === 'failed', cancelled: child.status === 'cancelled' }"
                    :open="isMessageEventExpanded(child.id)"
                    @toggle="setMessageEventExpanded(child.id, $event.target.open)"
                >
                  <summary>
                    <span class="timeline-icon system" aria-hidden="true"><el-icon><WarningFilled /></el-icon></span>
                    <span>{{ eventSummary(child) }}</span>
                  </summary>
                  <pre class="event-detail-inline">{{ eventDetail(child) }}</pre>
                </details>
              </div>
            </div>
          </details>

          <details
              v-else-if="event.event_type === 'model_retry'"
              class="retry-event"
              :open="isMessageEventExpanded(event.id)"
              @toggle="setMessageEventExpanded(event.id, $event.target.open)"
          >
            <summary>
              <span class="timeline-icon retry" aria-hidden="true"><el-icon><RefreshRight /></el-icon></span>
              <span>{{ eventSummary(event) }}</span>
            </summary>
            <pre class="retry-error-detail">{{ retryError(event) }}</pre>
          </details>

          <details
              v-else-if="event.event_type === 'attachment'"
              class="attachment-delivery-event"
              :open="isMessageEventExpanded(event.id)"
              @toggle="setMessageEventExpanded(event.id, $event.target.open)"
          >
            <summary>
              <span class="attachment-delivery-marker"><el-icon><Paperclip /></el-icon></span>
              <span>{{ eventAttachments(event).map((item) => item.file_name).join('、') || t('chat.attachmentMessage') }}</span>
            </summary>
            <div class="attachment-delivery-list">
              <template v-for="attachment in eventAttachments(event)" :key="attachment.id">
                <button
                    v-if="isImageAttachment(attachment)"
                    type="button"
                    class="attachment-delivery-item"
                    @click="previewEventAttachment(event, attachment)"
                >
                  <img
                      v-if="previewSrc(attachment, event)"
                      :src="previewSrc(attachment, event)"
                      :alt="attachment.file_name"
                      loading="lazy"
                  />
                  <span v-else class="user-attachment-placeholder">{{ attachment.file_name || '图片' }}</span>
                </button>
                <a
                    v-else
                    class="attachment-delivery-item attachment-delivery-link"
                    :href="previewSrc(attachment, event)"
                    target="_blank"
                    rel="noopener noreferrer"
                    :title="attachment.file_name"
                    @click="openEventAttachment(event, attachment, $event)"
                >
                  <span>{{ attachment.file_name }}</span>
                </a>
              </template>
            </div>
          </details>

          <div v-else-if="isSystemDeliveryEvent(event)" class="system-delivery-event" :class="[`system-delivery-${systemDeliveryType(event)}`, { failed: event.status === 'failed' || systemDeliveryFailed(event) }]">
            <div class="system-delivery-marker" aria-hidden="true"><el-icon><InfoFilled /></el-icon></div>
            <div class="system-delivery-content">
              <div class="system-delivery-label">{{ systemDeliveryLabel(event) }}</div>
              <div class="system-delivery-body">{{ systemDeliveryText(event) }}</div>
            </div>
          </div>

          <div v-else-if="event.event_type === 'model_reply'" class="model-reply-event">
            <div class="markdown-event-row">
              <span class="markdown-timeline-marker" aria-hidden="true"><el-icon><ChatDotRound /></el-icon></span>
              <div class="model-reply-body">
                <MarkdownContent :content="modelReplyText(event)" />
                <div class="model-reply-actions">
                  <span class="model-reply-action-right">
                    <el-button link size="small" @click="openRawMessage(event)">{{ t('chat.raw') }}</el-button>
                    <el-button link size="small" @click="copyModelReply(event)">{{ t('common.copy') }}</el-button>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <details
              v-else
              class="system-event"
              :class="{ failed: event.status === 'failed', cancelled: event.status === 'cancelled' }"
              :open="isMessageEventExpanded(event.id)"
              @toggle="setMessageEventExpanded(event.id, $event.target.open)"
          >
            <summary>
              <span class="timeline-icon system" aria-hidden="true"><el-icon><WarningFilled /></el-icon></span>
              <span>{{ eventSummary(event) }}</span>
            </summary>
            <pre class="event-detail-inline">{{ eventDetail(event) }}</pre>
          </details>
          <time class="event-time">{{ formatEventTime(event.updated_at || event.created_at) }}</time>
        </div>
      </div>
    </div>
    </template>
    <div v-if="turnTimelineEvents(turn).length" class="turn-actions">
      <span class="turn-action-left">
        <span class="reply-status-mark" :class="`status-${turnStatus(turn)}`">{{ turnStatusSymbol(turn) }}</span>
        <time>{{ formatEventTime(turnLastEvent(turn)?.updated_at || turnLastEvent(turn)?.created_at) }}</time>
        <el-popover
            placement="top"
            width="360"
            trigger="hover"
            popper-class="usage-popover"
        >
          <template #reference>
            <span class="usage-pill">{{ formatTokenUsage(totalTokenUsage(turnUsage(turn))) }}</span>
          </template>
          <div class="usage-detail">
            <div class="usage-detail-title">Token usage{{ turnUsage(turn)?.estimated ? t('chat.timeline.estimated') : '' }}</div>
            <div v-for="row in usageRows(turnUsage(turn))" :key="row.key" class="usage-detail-row">
              <span>{{ row.key }}</span>
              <strong>{{ row.value }}</strong>
            </div>
          </div>
        </el-popover>
      </span>
      <el-button link size="small" type="danger" @click="deleteTurn(turn)">{{ t('common.delete') }}</el-button>
    </div>
  </article>
</section>
    <el-dialog
      v-model="attachmentPreviewVisible"
      :title="attachmentPreview?.file_name || t('chat.timeline.imagePreview')"
      width="min(900px, 92vw)"
      append-to-body
      :lock-scroll="false"
    >
      <img v-if="attachmentPreview" class="attachment-preview-image" :src="attachmentPreview.url" :alt="attachmentPreview.file_name" />
    </el-dialog>
    <el-dialog v-model="rawMessageDialogVisible" width="720px" append-to-body @keydown="handleRawDialogKeydown" :lock-scroll="false">
      <template #header>
        <div class="raw-dialog-header">
          <span class="raw-dialog-title">{{ rawMessageTitle }}</span>
          <span class="raw-dialog-hint">{{ t('chat.selectAllHint', { selectShortcut: 'Command/Ctrl+A', copyShortcut: 'Command/Ctrl+C' }) }}</span>
        </div>
      </template>
      <pre ref="rawContentPre" class="raw-message-view" tabindex="0">{{ rawMessageContent }}</pre>
      <template #footer>
        <el-button @click="copyRawContent">{{ copyRawLabel }}</el-button>
        <el-button @click="rawMessageDialogVisible = false">{{ t('common.cancel') }}</el-button>
      </template>
    </el-dialog>
</template>

<style scoped>
.conversation-timeline {
  display: flex;
  flex-direction: column;
  gap: 28px;
  box-sizing: border-box;
  width: 100%;
  min-height: 100%;
  min-width: 0;
  max-width: 100%;
  padding: 4px 8px 24px;
  color: var(--jb-text);
}
.conversation-turn {
  display: flex;
  flex-direction: column;
  gap: 18px;
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
}
.user-event-row {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin-bottom: 2px;
}
.user-message {
  box-sizing: border-box;
  width: fit-content;
  max-width: 95%;
  border: 1px solid var(--soft-border);
  border-radius: 9px 9px 3px 9px;
  background: color-mix(in srgb, var(--jb-accent) 12%, var(--jb-panel));
  padding: 10px 12px;
  color: var(--jb-text);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.user-message-text {
  max-width: 100%;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.user-time {
  display: block;
  margin: 3px 2px 0;
  color: var(--jb-muted);
  font-size: 11px;
  line-height: 1.3;
}
.assistant-timeline { box-sizing: border-box; width: 95%; max-width: 95%; min-width: 0; padding: 0; }
.timeline-track {
  position: relative;
  min-width: 0;
  max-width: 100%;
  display: grid;
  gap: 0;
  margin-top: 8px;
  padding: 0;
}
.timeline-track::before {
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 8px;
  z-index: 0;
  width: 2px;
  border-radius: 999px;
  background: var(--jb-accent);
  box-shadow: 0 0 10px color-mix(in srgb, var(--jb-accent) 75%, transparent);
  content: '';
}
.timeline-event-row {
  position: relative;
  max-width: 100%;
  min-width: 0;
  padding: 0 0 10px 28px;
  color: var(--jb-text);
}
.timeline-append-event {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  width: 100%;
  margin: 10px 0;
}
.timeline-append-event .user-message {
  max-width: 86%;
}
.timeline-append-event .event-time {
  margin-right: 2px;
  text-align: right;
}

.conversation-turn,
.assistant-timeline,
.timeline-track,
.timeline-event-row,
.reasoning-event,
.tool-event,
.retry-event,
.system-event,
.model-reply-event {
  max-width: 100%;
  min-width: 0;
}
.reasoning-event,
.tool-event,
.retry-event,
.system-event,
.subagent-event {
  position: static;
}
.reasoning-event,
.tool-event {
  margin: 0;
  border: 0;
  background: transparent;
}
.reasoning-event summary,
.tool-event summary,
.retry-event summary,
.system-event summary,
.subagent-event summary,
.attachment-delivery-event summary,
.system-delivery-label {
  display: flex;
  min-width: 0;
  min-height: 18px;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
  color: var(--jb-muted);
  font-size: 13px;
  line-height: 18px;
}
.reasoning-event summary,
.tool-event summary,
.retry-event summary,
.system-event summary,
.subagent-event summary,
.attachment-delivery-event summary {
  cursor: pointer;
}
.reasoning-event summary::-webkit-details-marker,
.tool-event summary::-webkit-details-marker,
.retry-event summary::-webkit-details-marker,
.system-event summary::-webkit-details-marker,
.subagent-event summary::-webkit-details-marker { display: none; }
.reasoning-event summary > span:last-child,
.tool-event summary > span:last-child,
.retry-event summary > span:last-child,
.system-event summary > span:last-child,
.subagent-event summary > span:last-child,
.attachment-delivery-event summary > span:last-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 18px;
}
.timeline-icon,
.markdown-timeline-marker,
.system-delivery-marker,
.attachment-delivery-marker {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 1;
  display: inline-flex;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  align-items: center;
  justify-content: center;
  margin: 0;
  border-radius: 50%;
  background: var(--jb-panel);
  color: var(--jb-text);
  box-shadow: 0 0 0 1px var(--soft-border);
}
.timeline-icon .el-icon,
.markdown-timeline-marker .el-icon,
.system-delivery-marker .el-icon,
.attachment-delivery-marker .el-icon {
  font-size: 12px;
  color: inherit;
}
.timeline-icon.reasoning { color: var(--jb-text); }
.timeline-icon.tool { color: var(--jb-text); }
.timeline-icon.subagent { color: var(--jb-accent); }
.timeline-icon.retry,
.timeline-icon.system,
.system-delivery-marker { color: #d29922; }
.markdown-timeline-marker { color: var(--jb-accent); }
.attachment-delivery-marker { color: var(--jb-accent); }
.timeline-icon.tool.status-completed { color: #16a34a; }
.timeline-icon.tool.status-failed { color: #dc2626; }
.timeline-icon.tool.status-running { color: #d97706; }
.timeline-icon.tool.status-cancelled { color: var(--jb-muted); }
.reasoning-detail {
  max-width: 100%;
  max-height: 220px;
  overflow: auto;
  overflow-x: hidden;
  margin: 7px 0 4px;
  border-left: 2px solid var(--soft-fill);
  padding: 8px 12px;
  color: var(--jb-text);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  user-select: text;
  -webkit-user-select: text;
}
.tool-event.status-running summary { color: #d97706; }
.tool-event.status-completed summary { color: #16a34a; }
.tool-event.status-failed summary { color: #dc2626; }
.tool-event.status-cancelled summary { color: var(--jb-muted); }
.tool-event-detail {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  margin: 8px 0 6px;
  padding-left: 1px;
}
.subagent-event > summary {
  list-style: none;
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.nested-timeline {
  margin: 6px 0 2px 10px;
  border-left: 1px dashed var(--soft-border);
  padding-left: 12px;
}
.nested-event-row {
  margin-left: 0;
}
.nested-timeline .timeline-icon,
.nested-timeline .markdown-timeline-marker {
  left: 0;
}
.tool-value-pane { min-width: 0; overflow: hidden; }
.tool-value-pane strong {
  display: block;
  margin-bottom: 6px;
  color: var(--jb-muted);
  font-size: 12px;
  font-weight: 600;
}
.tool-value-pane pre {
  max-width: 100%;
  min-width: 0;
  max-height: 180px;
  overflow: auto;
  margin: 0;
  background: transparent;
  padding: 0 0 0 10px;
  color: var(--jb-muted);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  font: 12px/1.55 Consolas, monospace;
  user-select: text;
  -webkit-user-select: text;
}
.retry-event,
.system-event { margin: 0; padding: 0; color: #d29922; }
.retry-event summary { color: #b45309; }
.retry-error-detail,
.event-detail-inline {
  max-width: 100%;
  max-height: 220px;
  overflow: auto;
  margin: 4px 0 0;
  border: 0;
  border-left: 2px solid currentColor;
  background: transparent;
  padding: 8px 10px;
  color: #7c2d12;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  font: 12px/1.6 Consolas, monospace;
  user-select: text;
  -webkit-user-select: text;
}
.system-event.failed,
.system-event.failed summary { color: #f85149; }
.system-event.cancelled,
.system-event.cancelled summary { color: #b45309; }
.markdown-event-row {
  display: block;
  min-width: 0;
  max-width: 100%;
  margin: 0;
}
.attachment-delivery-event {
 display: block;
 min-width: 0;
 margin: 0;
 color: var(--jb-accent);
}
.attachment-delivery-event summary {
  cursor: pointer;
}
.attachment-delivery-event summary::-webkit-details-marker { display: none; }
.attachment-delivery-list {
 display: flex;
 flex-wrap: wrap;
 gap: 6px;
 margin: 7px 0 4px 28px;
}
.attachment-delivery-item {
 display: flex;
 width: 112px;
 height: 84px;
 align-items: center;
 justify-content: center;
 overflow: hidden;
 border: 1px solid color-mix(in srgb, var(--jb-accent) 28%, var(--soft-border));
 border-radius: 6px;
 padding: 0;
 color: #0369a1;
 background: color-mix(in srgb, var(--jb-accent) 10%, var(--jb-panel));
 cursor: pointer;
}
.attachment-delivery-link {
 text-decoration: none;
 padding: 8px;
 text-align: center;
}
.attachment-delivery-link span {
 overflow: hidden;
 display: -webkit-box;
 -webkit-box-orient: vertical;
 -webkit-line-clamp: 3;
 word-break: break-all;
}
.attachment-delivery-item img { display: block; width: 100%; height: 100%; object-fit: cover; }
.system-delivery-event {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin: 0;
  color: var(--jb-text);
}
.system-delivery-content {
  min-width: 0;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.system-delivery-label {
  margin-bottom: 6px;
  font-weight: 600;
  white-space: normal;
}
.system-delivery-body {
  min-width: 0;
  color: var(--jb-text);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.system-delivery-event.failed .system-delivery-label {
  color: #f85149;
}
.model-reply-event {
  min-width: 0;
}
.model-reply-body {
  min-width: 0;
  max-width: 100%;
}
.model-reply-body { min-width: 0; max-width: 100%; color: var(--jb-text); font-size: 13px; line-height: 1.65; }
.model-reply-body .markdown-body { margin: 0; }
.model-reply-body .markdown-body > :first-child {
  margin-top: 0;
  line-height: 18px;
}
.markdown-event-row { min-height: 18px; }

.model-reply-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
  border-top: 1px solid var(--soft-border);
  padding-top: 5px;
}
.model-reply-action-right { display: inline-flex; gap: 4px; }
.event-time {
  display: block;
  margin: 4px 0 0;
  color: var(--jb-muted);
  font-size: 11px;
  line-height: 1.3;
}
.message-event-detail pre { max-height: 60vh; overflow: auto; white-space: pre-wrap; word-break: break-word; }

.user-event-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.user-attachment {
  display: block;
  max-width: 180px;
  overflow: hidden;
  border: 1px solid var(--soft-border);
  border-radius: 6px;
  background: var(--jb-panel);
  padding: 3px;
  color: var(--jb-muted);
  cursor: pointer;
  font-size: 10px;
  text-align: left;
}
.user-attachment-placeholder {
  display: flex;
  min-width: 72px;
  min-height: 48px;
  align-items: center;
  justify-content: center;
  padding: 8px;
  color: var(--jb-muted);
  font-size: 10px;
}
.user-attachment img {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  max-height: 220px;
  object-fit: contain;
  border-radius: 4px;
}
.user-attachment span {
  display: block;
  overflow: hidden;
  padding: 5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-attachment-link {
  color: var(--jb-accent);
  text-decoration: none;
}
.user-attachment-link:hover {
  text-decoration: underline;
}
.turn-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  box-sizing: border-box;
  width: 100%;
  margin-top: 8px;
  border-top: 1px solid var(--soft-border);
  padding: 8px 0 0;
}
.turn-action-left {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  color: var(--jb-muted);
  font-size: 11px;
}
.reply-status-mark {
  display: inline-flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--jb-muted);
  color: var(--jb-panel);
  font-size: 10px;
  font-weight: 700;
}
.reply-status-mark.status-completed { background: #22c55e; }
.reply-status-mark.status-running { background: #3b82f6; animation: reply-status-spin 1s linear infinite; }
@keyframes reply-status-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.reply-status-mark.status-failed { background: #ef4444; }
.reply-status-mark.status-cancelled { background: #f59e0b; }
.usage-pill {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--soft-border);
  border-radius: 999px;
  background: color-mix(in srgb, var(--jb-accent) 12%, var(--jb-panel));
  padding: 1px 7px;
  color: var(--jb-accent);
  font-size: 11px;
  line-height: 1.5;
  white-space: nowrap;
}
.usage-detail { display: grid; gap: 6px; }
.usage-detail-title { color: var(--jb-text); font-size: 12px; font-weight: 700; }
.usage-detail-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: start;
  gap: 12px;
  color: var(--jb-muted);
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
}
.usage-detail-row span { min-width: 0; overflow-wrap: anywhere; word-break: break-word; }
.usage-detail-row strong { justify-self: end; color: var(--jb-text); text-align: right; white-space: nowrap; }
.reasoning-detail, .tool-value-pane pre, .event-detail-inline { user-select: text; -webkit-user-select: text; cursor: text; }

:deep(.usage-popover) { padding: 12px 14px; }
.raw-message-view {
  height: 460px;
  min-height: 0;
  max-height: 60vh;
  border: 1px solid var(--soft-border);
  border-radius: 8px;
  background: var(--jb-input);
  padding: 12px;
  overflow-y: auto;
  overflow-x: hidden;
  white-space: pre-wrap;
  word-break: break-word;
}

.raw-dialog-header {
  display: grid;
  gap: 2px;
}

.raw-dialog-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--jb-text);
}

.raw-dialog-hint {
  font-size: 12px;
  color: var(--jb-muted);
}
.attachment-preview-image {
  display: block;
  width: 100%;
  max-height: 70vh;
  object-fit: contain;
}
</style>
