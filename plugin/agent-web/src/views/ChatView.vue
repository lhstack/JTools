<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, Close, Cpu, Delete, EditPen, MagicStick, MoreFilled, Paperclip, Plus, Refresh, Setting, VideoPause } from '@element-plus/icons-vue'
import { hostState as s, api, invoke } from '../bridge/jcefBridge'
import { consumeMessageTask, messageTasksForSession, replaceMessageTasks } from '../realtime/messageTaskStore.js'
import ConversationTimeline from '../components/chat/ConversationTimeline.vue'
import MessageTaskPanel from '../components/chat/MessageTaskPanel.vue'
import ModelParamsForm from '../components/model/ModelParamsForm.vue'
import { isImageAttachment, attachmentUrl, loadAttachmentPreview } from '../components/chat/attachmentUtils.js'
import { effectiveModelApi, promptCacheKeyFor, visibleAdditionalParamsText } from '../modelParams.js'
import {
  defaultContextWindow,
  defaultMaxHistoryMessages,
  defaultModalities,
  executionParamsWithDefaults,
  modelParamsWithDefaults,
  modelStreamValue,
  pruneEmptyParams,
  sanitizeModelParamsForApi,
} from '../modelParamSchema.js'
import { assignReactive, optionValue, parseJsonObject } from '../modelSettings.js'
import {
  modelReasoningLevel,
  modelThinkingConfig,
  reasoningLabel,
  reasoningOptionsFor,
  thinkingConfigForType,
  validateReasoningOverride,
} from '../reasoningSettings.js'


const currentSession = computed(() => s.currentSession || null)
const providers = computed(() => s.providers || [])
const prompts = computed(() => s.prompts || [])
const environments = computed(() => s.environments || [])
const selectedProviderId = ref(null)
const selectedModelId = ref(null)
const selectedPromptId = ref(null)
const reasoningLevel = ref(null)
const reasoningConfig = ref(null)
const modelSettingsPopoverVisible = ref(false)
const reasoningPopoverVisible = ref(false)
const moreMenuVisible = ref(false)
const modelSettingsMenu = ref(null)
const reasoningSettingsMenu = ref(null)
const settingsSelectExpanded = ref(false)
const advancedParamsVisible = ref(false)
const advancedParamsSaving = ref(false)
const contextVisible = ref(false)
const context = computed(() => s.context || { estimated_tokens: 0, context_window: null, percent: null, compaction_status: 'idle', compaction_agent_configured: false })
const runtimeSkills = computed(() => Array.isArray(s.skills) ? s.skills : [])
const skillsHint = computed(() => {
  const skills = runtimeSkills.value
  if (!skills.length) return '未启用技能'
  if (skills.length <= 2) return skills.map((item) => item.name).join('、')
  return `${skills.slice(0, 2).map((item) => item.name).join('、')} 等 ${skills.length} 个`
})

const compactionInProgress = computed(() => context.value.compaction_status === 'running')
const sessionRunning = computed(() => tasks.value.some((item) => ['pending', 'processing'].includes(item.status)))
const contextHint = computed(() => {
  if (compactionInProgress.value) return '压缩中'
  const tokens = Number(context.value.estimated_tokens || 0).toLocaleString()
  const window = context.value.context_window ? ` / ${Number(context.value.context_window).toLocaleString()}` : ' / 未设置上限'
  return `${tokens}${window}`
})
const sessionMetrics = computed(() => {
  const items = events.value
  const turnIds = new Set(items.map((event) => event.turn_id).filter(Boolean))
  const times = items.map((event) => parseEventTime(event.updated_at || event.created_at)).filter((value) => Number.isFinite(value))
  const durationMs = times.length >= 2 ? Math.max(0, Math.max(...times) - Math.min(...times)) : (times.length === 1 ? 0 : null)
  const usage = context.value.token_usage && typeof context.value.token_usage === 'object' ? context.value.token_usage : {}
  const total = totalTokenUsage(usage)
  return {
    turns: turnIds.size,
    blocks: items.length,
    tools: items.filter((event) => event.event_type === 'tool_call').length,
    duration: formatDuration(durationMs),
    total: total > 0 ? total : null,
    usageRows: flattenUsageRows(usage),
  }
})
function parseEventTime(value) {
  if (!value) return null
  const date = new Date(String(value).replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? null : date.getTime()
}
function formatDuration(milliseconds) {
  if (milliseconds == null) return '—'
  const seconds = Math.max(0, Math.round(milliseconds / 1000))
  const minutes = Math.floor(seconds / 60)
  const remain = seconds % 60
  if (minutes <= 0) return `${remain}s`
  return `${minutes}m ${remain}s`
}
function totalTokenUsage(usage) {
  const rows = flattenUsageRows(usage)
  return rows.reduce((sum, row) => sum + (Number(row.value) || 0), 0)
}
function flattenUsageRows(usage, prefix = '') {
  if (!usage || typeof usage !== 'object') return []
  const rows = []
  Object.entries(usage).forEach(([key, value]) => {
    const next = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object' && !Array.isArray(value)) rows.push(...flattenUsageRows(value, next))
    else if (value != null && value !== '') rows.push({ key: next, value })
  })
  return rows
}
const sessionModelParams = reactive({
  api: '',
  context_window: null,
  max_history_messages: null,
  modalities: [],
  model_params: {},
  execution_params: {},
  additional_params_text: '',
})
const activeProvider = computed(() => providers.value.find((item) => Number(item.id) === Number(selectedProviderId.value)) || null)
const availableModels = computed(() => activeProvider.value?.models || [])
const activeModel = computed(() => availableModels.value.find((item) => Number(item.id) === Number(selectedModelId.value)) || null)
const selectedOpenAiCompatible = computed(() =>
  (activeProvider.value?.provider_config?.openai_provider_type || currentSession.value?.model_snapshot?.openai_provider_type) === 'compatible'
)
const codingEffectiveApi = computed(() =>
  effectiveModelApi(
    sessionModelParams.api || activeModel.value?.api || currentSession.value?.model_snapshot?.api,
    activeProvider.value?.api || currentSession.value?.model_snapshot?.api || 'completions',
  )
)
const promptCacheKeyDefault = computed(() => promptCacheKeyFor({
  api: codingEffectiveApi.value,
  providerId: selectedProviderId.value,
  modelId: selectedModelId.value,
  environmentId: currentSession.value?.coding_environment_id || 'none',
}).replace('awake-claw:', 'jtools:'))
const modelSettingsLabel = computed(() => {
  if (!activeProvider.value || !activeModel.value) return '模型'
  return `${activeProvider.value.name} / ${activeModel.value.display_name || activeModel.value.alias}`
})
const reasoningOptions = computed(() => reasoningOptionsFor(activeProvider.value, activeModel.value))
const activeThinkingType = computed({
  get: () => reasoningConfig.value?.type || null,
  set: (value) => {
    reasoningConfig.value = thinkingConfigForType(value, activeModel.value, reasoningConfig.value)
  },
})
const reasoningSettingsLabel = computed(() => {
  if (activeProvider.value?.kind === 'anthropic') {
    const config = reasoningConfig.value
    const state = !config || config.type === 'disabled' ? '关闭' : config.type === 'adaptive' ? '自适应' : '开启'
    const display = config?.display === 'summarized' ? '摘要' : '省略'
    const effort = reasoningLevel.value ? reasoningLabel(reasoningLevel.value) : '继承模型'
    return `${state} · ${display} · ${effort}`
  }
  const level = reasoningLevel.value
  if (!level || level === 'none') return '推理关闭'
  return `推理 ${reasoningLabel(level)}`
})

watch(
  () => [currentSession.value?.id, currentSession.value?.provider_id, currentSession.value?.model_id, currentSession.value?.prompt_id, currentSession.value?.reasoning_level, currentSession.value?.reasoning_config],
  () => {
    selectedProviderId.value = currentSession.value?.provider_id || null
    selectedModelId.value = currentSession.value?.model_id || null
    selectedPromptId.value = currentSession.value?.prompt_id || null
    reasoningLevel.value = currentSession.value?.reasoning_level || modelReasoningLevel(activeModel.value)
    reasoningConfig.value = activeProvider.value?.kind === 'anthropic'
      ? (currentSession.value?.reasoning_config || modelThinkingConfig(activeModel.value))
      : null
  },
  { immediate: true },
)

const prompt = ref('')
const messageList = ref(null)
const autoFollowConversation = ref(true)
const sending = ref(false)
const composerExpanded = ref(false)
const dragging = ref(false)
const sessionDialogVisible = ref(false)
const sessionName = ref('')
const sessionType = ref('project')
const creatingSession = ref(false)
const queueEditVisible = ref(false)
const queueEditItem = ref(null)
const queueEditPrompt = ref('')
const queueEditSaving = ref(false)
const appendDialogVisible = ref(false)
const appendTarget = ref(null)
const appendPrompt = ref('')
const appendAttachments = ref([])
const appendSaving = ref(false)
const polishVisible = ref(false)
const polishStatus = ref('running')
const polishError = ref('')
const polishResults = ref([])
const polishActive = ref(0)
const polishTaskId = ref('')
const POLISH_POLL_INTERVAL = 600
let polishPollTimer = null
const SELECTED_SESSION_CACHE_KEY = 'jtools:selected-session:v1'
const drafts = computed(() => s.drafts || [])
const draftPreviewUrls = reactive({})
watch(
  drafts,
  (items) => {
    items.filter((item) => isImageAttachment(item) && (item.path || item.id) && !draftPreviewUrls[item.id]).forEach(async (item) => {
      const url = await loadAttachmentPreview(item, s.currentSessionId)
      if (url) draftPreviewUrls[item.id] = url
    })
  },
  { immediate: true, deep: true },
)
function draftPreviewSrc(item) {
  return item?.previewUrl || draftPreviewUrls[item?.id] || ''
}
const fileContextEnabled = computed(() => !!s.fileContextEnabled)
const fileContextLabel = computed(() => s.fileContextLabel || '文件上下文')
const fileContextChip = computed(() => (fileContextEnabled.value ? s.fileContextChip : null) || null)
const fileContextTitle = computed(() => (
  fileContextEnabled.value
    ? '关闭文件上下文：不再附带路径/选中范围'
    : '开启文件上下文：附带路径；有选区时附带所有选中范围'
))
const fileContextTitleHtml = computed(() => escapeTooltipHtml(fileContextTitle.value))
const fileContextChipTooltip = computed(() => escapeTooltipHtml(fileContextChip.value?.tooltip || fileContextChip.value?.label || ''))
function escapeTooltipHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/\n/g, '<br/>')
}
async function toggleFileContext() {
  if (fileContextEnabled.value) {
    await invoke('fileContext.toggle', { enabled: false })
    return
  }
  try {
    await ElMessageBox.confirm(
      '开启后，编辑器中当前打开的文件路径将发送给 AI。注意：仅传递文件路径，不会传递文件内容。',
      '开启文件上下文？',
      {
        confirmButtonText: '开启',
        cancelButtonText: '取消',
        type: 'info',
        distinguishCancelAndClose: true,
      },
    )
    await invoke('fileContext.toggle', { enabled: true })
  } catch {
    // 用户取消
  }
}
async function closeFileContextChip() {
  await invoke('fileContext.toggle', { enabled: false })
}
const events = computed(() => s.events || [])
const tasks = computed(() => messageTasksForSession(s.currentSessionId))
const processingTask = computed(() => tasks.value.find((item) => item.status === 'processing') || null)
const previewSrc = ref('')
const previewVisible = ref(false)
function openAttachment(att) {
  previewAttachment(att)
}

async function previewAttachment(att) {
  if (!att) return
  if (isImageAttachment(att)) {
    const url = att.previewUrl || att.url || await loadAttachmentPreview(att, s.currentSessionId)
    if (url) {
      previewSrc.value = url
      previewVisible.value = true
      return
    }
  }
  if (att.id && s.currentSessionId) {
    await invoke('attachment.openById', { sessionId: String(s.currentSessionId), id: String(att.id) })
    return
  }
  if (att.path) invoke('attachment.open', { text: att.path })
}

watch(
  () => s.historyRevision,
  (revision, previous) => {
    if (!revision || revision === previous) return
    followIncomingConversation()
  },
  { flush: 'post' }
)

watch(
  () => s.inputRestore?.sequence,
  async (sequence) => {
    if (!sequence) return
    prompt.value = s.inputRestore.text || ''
    const attachments = Array.isArray(s.inputRestore.attachments) ? s.inputRestore.attachments : []
    try {
      await invoke('draft.restore', { attachments })
    } catch (error) {
      ElMessage.error(error?.message || String(error) || '恢复附件失败')
    }
  }
)

watch(
  () => [s.currentSessionId, events.value.length],
  ([sessionId], previous = []) => {
    persistSelectedSession(sessionId)
    if (sessionId !== previous[0]) {
      autoFollowConversation.value = true
      scrollMessagesToBottom()
    }
  },
  { flush: 'post' }
)

watch(
  () => events.value.map((item) => [item.id, item.status, item.revision, item.summary]),
  () => {
    followIncomingConversation()
  },
  { flush: 'post' }
)

function persistSelectedSession(sessionId) {
  try {
    if (sessionId) localStorage.setItem(SELECTED_SESSION_CACHE_KEY, String(sessionId))
    else localStorage.removeItem(SELECTED_SESSION_CACHE_KEY)
  } catch {
    // Browser cache is only an initial rendering optimization.
  }
}

function shouldFollowConversation() {
  const element = messageList.value
  if (!element) return true
  return element.scrollHeight - element.scrollTop - element.clientHeight < 96
}

function handleConversationScroll() {
  autoFollowConversation.value = shouldFollowConversation()
}

function scrollMessagesToBottom() {
  nextTick(() => {
    const element = messageList.value
    if (!element) return
    element.scrollTop = element.scrollHeight
    requestAnimationFrame(() => {
      if (messageList.value === element) element.scrollTop = element.scrollHeight
    })
  })
}

function followIncomingConversation() {
  if (!(autoFollowConversation.value || shouldFollowConversation())) return
  autoFollowConversation.value = true
  scrollMessagesToBottom()
}

function eventAttachmentUrl(attachment, sessionId = s.currentSessionId) {
  return attachmentUrl(attachment, sessionId)
}

async function loadEventDetail(eventId) {
  return api('timeline.event', { id: String(eventId) })
}

function onEventLoaded(detail) {
  if (!detail?.id) return
  const next = [...(s.events || [])]
  const index = next.findIndex((item) => Number(item.id) === Number(detail.id))
  if (index >= 0) next[index] = { ...next[index], ...detail }
  else next.push(detail)
  s.events = next
}

function deleteTurn(turn) {
  const turnId = turn?.turnId || turn?.turn_id
  if (!turnId) return
  invoke('timeline.deleteTurn', { turnId })
}

async function uploadTaskAttachments() {
  return await api('appendAttachment.choose')
}

async function pasteTaskAttachments() {
  return await api('appendAttachment.paste')
}

const cancellingTaskIds = new Set()

async function cancelProcessing(task) {
  const key = String(task?.id || '')
  if (!key || cancellingTaskIds.has(key)) return
  cancellingTaskIds.add(key)
  try {
    await invoke('queue.stop', { id: key })
  } catch (error) {
    ElMessage.error(error?.message || String(error) || '取消失败')
  } finally {
    cancellingTaskIds.delete(key)
  }
}

async function cancelQueued(task) {
  const key = String(task?.id || '')
  if (!key || cancellingTaskIds.has(key)) return
  cancellingTaskIds.add(key)
  try {
    await invoke('queue.stop', { id: key })
    consumeMessageTask({
      type: 'message_task',
      id: task.id,
      session_id: s.currentSessionId,
      status: 'cancelled',
      deleted: true,
    })
  } catch (error) {
    ElMessage.error(error?.message || String(error) || '取消失败')
    loadMessageTasks().catch(() => {})
  } finally {
    cancellingTaskIds.delete(key)
  }
}

async function updateQueued({ task, content, attachments }) {
  await invoke('queue.edit', { id: String(task.id), text: content, attachments: attachments || [] })
}

async function appendToTurn({ task, content, attachments }) {
  await invoke('queue.append', { turnId: task.turn_id, text: content, attachments: attachments || [] })
}


function snapshotSource() {
  const snapshot = currentSession.value?.model_snapshot || {}
  const model = activeModel.value
  const provider = activeProvider.value
  if (
    snapshot
    && Number(snapshot.id) === Number(selectedModelId.value)
    && Number(snapshot.provider_id) === Number(selectedProviderId.value)
  ) {
    return snapshot
  }
  if (!model || !provider) return snapshot
  return {
    ...snapshot,
    provider_id: provider.id,
    provider_label: provider.name,
    id: model.id,
    model_label: model.alias,
    display_name: model.display_name || null,
    provider_kind: provider.kind,
    openai_provider_type: provider.provider_config?.openai_provider_type || 'official',
    model_id: model.model_id,
    api: provider.kind === 'anthropic' ? '' : effectiveModelApi(model.api, provider.api || 'completions'),
    anthropic_version: provider.anthropic_version || null,
    model_params: model.model_params,
    execution_params: model.execution_params,
    additional_params: model.additional_params,
    context_window: model.context_window ?? snapshot.context_window,
    max_history_messages: snapshot.max_history_messages ?? currentSession.value?.max_history_rounds,
    modalities: model.modalities || snapshot.modalities,
  }
}

function applyPromptCacheKeyDefault(previousDefault = '') {
  sessionModelParams.model_params ||= {}
  const current = sessionModelParams.model_params.prompt_cache_key
  if (!current || current === previousDefault) {
    sessionModelParams.model_params.prompt_cache_key = promptCacheKeyDefault.value
  }
}

function hydrateSessionModelParams(snapshot) {
  const providerKind = snapshot?.provider_kind || activeProvider.value?.kind || ''
  const api = snapshot?.api || activeModel.value?.api || activeProvider.value?.api || 'completions'
  assignReactive(sessionModelParams, {
    api: snapshot?.api || '',
    context_window: defaultContextWindow(snapshot?.context_window),
    max_history_messages: defaultMaxHistoryMessages(snapshot?.max_history_messages ?? currentSession.value?.max_history_rounds),
    modalities: defaultModalities(snapshot?.modalities),
    model_params: modelParamsWithDefaults(providerKind, api, snapshot?.model_params),
    execution_params: executionParamsWithDefaults(snapshot?.execution_params),
    additional_params_text: visibleAdditionalParamsText(snapshot?.additional_params),
  })
  applyPromptCacheKeyDefault()
}

function sessionModelSnapshotFromParams() {
  applyPromptCacheKeyDefault()
  const snapshot = JSON.parse(JSON.stringify(snapshotSource()))
  const providerKind = snapshot.provider_kind || activeProvider.value?.kind || ''
  if (providerKind === 'anthropic') {
    delete snapshot.api
  } else {
    snapshot.api = optionValue(sessionModelParams.api) || snapshot.api || 'completions'
  }
  const compatible = selectedOpenAiCompatible.value || snapshot.openai_provider_type === 'compatible'
  const sanitized = sanitizeModelParamsForApi(
    providerKind,
    snapshot.api || 'completions',
    sessionModelParams.model_params,
    compatible,
  )
  snapshot.model_params = pruneEmptyParams(
    modelParamsWithDefaults(providerKind, snapshot.api || 'completions', sanitized),
  ) || null
  snapshot.execution_params = pruneEmptyParams(
    executionParamsWithDefaults(sessionModelParams.execution_params),
  ) || null
  snapshot.stream = modelStreamValue(snapshot.model_params) ?? true
  snapshot.additional_params = parseJsonObject(sessionModelParams.additional_params_text)
  snapshot.context_window = defaultContextWindow(sessionModelParams.context_window)
  snapshot.max_history_messages = defaultMaxHistoryMessages(sessionModelParams.max_history_messages)
  snapshot.modalities = defaultModalities(sessionModelParams.modalities)
  return snapshot
}

async function persistModelSettings({ snapshot = null } = {}) {
  if (!currentSession.value || !selectedProviderId.value || !selectedModelId.value) {
    throw new Error('请先选择供应商和模型')
  }
  const modelChanged = Number(selectedProviderId.value) !== Number(currentSession.value.provider_id)
    || Number(selectedModelId.value) !== Number(currentSession.value.model_id)
  const settings = {
    provider_id: selectedProviderId.value,
    model_id: selectedModelId.value,
    prompt_id: selectedPromptId.value,
    reasoning_level: reasoningLevel.value,
    reasoning_config: activeProvider.value?.kind === 'anthropic' ? reasoningConfig.value : null,
    model_snapshot: snapshot || (modelChanged ? null : currentSession.value.model_snapshot),
    max_history_rounds: snapshot?.max_history_messages ?? currentSession.value.max_history_rounds,
  }
  await api('session.modelSettings.save', settings)
}

function handleProviderChange() {
  selectedModelId.value = availableModels.value[0]?.id || null
  resetActiveReasoning()
}

function handleActiveModelChange() {
  resetActiveReasoning()
}

function resetActiveReasoning() {
  reasoningLevel.value = modelReasoningLevel(activeModel.value)
  reasoningConfig.value = activeProvider.value?.kind === 'anthropic' ? modelThinkingConfig(activeModel.value) : null
}

function openModelSettings() {
  reasoningPopoverVisible.value = false
  moreMenuVisible.value = false
  modelSettingsPopoverVisible.value = true
}

function openReasoningSettings() {
  modelSettingsPopoverVisible.value = false
  moreMenuVisible.value = false
  reasoningPopoverVisible.value = true
}

function handleSettingsSelectVisible(visible) {
  settingsSelectExpanded.value = visible
}

function handleSettingsOutsidePointerDown(event) {
  const target = event.target
  if (!(target instanceof Element)) return
  if (moreMenuVisible.value && !target.closest('.chat-more-popper, .chat-top')) {
    moreMenuVisible.value = false
  }
  if (!modelSettingsPopoverVisible.value && !reasoningPopoverVisible.value) return
  if (target.closest('.model-settings-trigger, .reasoning-settings-trigger')) return
  if (target.closest('.coding-settings-popper, .el-select__popper')) return
  if (modelSettingsMenu.value?.contains(target) || reasoningSettingsMenu.value?.contains(target)) return
  if (settingsSelectExpanded.value) return
  modelSettingsPopoverVisible.value = false
  reasoningPopoverVisible.value = false
}

function saveModelSettings() {
  persistModelSettings().then(() => {
    modelSettingsPopoverVisible.value = false
  }).catch((error) => ElMessage.error(error.message || String(error)))
}

function saveReasoningSettings() {
  try {
    validateReasoningOverride(activeProvider.value, reasoningConfig.value)
    persistModelSettings().then(() => {
      reasoningPopoverVisible.value = false
    }).catch((error) => ElMessage.error(error.message || String(error)))
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function openAdvancedParams() {
  if (!currentSession.value) return
  modelSettingsPopoverVisible.value = false
  reasoningPopoverVisible.value = false
  moreMenuVisible.value = false
  hydrateSessionModelParams(snapshotSource())
  advancedParamsVisible.value = true
}

async function saveAdvancedParams() {
  if (!currentSession.value || !selectedProviderId.value || !selectedModelId.value) return
  advancedParamsSaving.value = true
  try {
    await persistModelSettings({ snapshot: sessionModelSnapshotFromParams() })
    advancedParamsVisible.value = false
    ElMessage.success('高级参数已保存，下一轮请求生效')
  } catch (error) {
    ElMessage.error(error.message || String(error))
  } finally {
    advancedParamsSaving.value = false
  }
}

function formatContextPercent(percent) {
  const value = Number(percent || 0)
  return Number.isFinite(value) ? `${value.toFixed(value >= 10 ? 0 : 1)}%` : '0%'
}

async function openContextDialog() {
  if (!s.currentSessionId) return
  try {
    const snapshot = await api('context.get')
    Object.assign(s, { context: snapshot })
  } catch (error) {
    ElMessage.error(error.message || String(error))
  }
  contextVisible.value = true
}

async function compactContext() {
  if (!s.currentSessionId || compactionInProgress.value || sessionRunning.value) return
  if (!context.value.compaction_agent_configured) {
    ElMessage.error('未配置上下文压缩 Agent（coding.compaction_agent_id）')
    return
  }
  try {
    const result = await api('context.compact')
    if (result) Object.assign(s, { context: result })
    ElMessage.success('已开始压缩上下文')
  } catch (error) {
    ElMessage.error(error.message || String(error))
    try {
      const snapshot = await api('context.get')
      Object.assign(s, { context: snapshot })
    } catch (_) {}
  }
}

async function loadMessageTasks(sessionId = s.currentSessionId) {
  if (!sessionId) {
    replaceMessageTasks(sessionId, [])
    return
  }
  const data = await api('session.tasks', { id: String(sessionId) })
  if (Number(sessionId) === Number(s.currentSessionId)) replaceMessageTasks(sessionId, data?.tasks || [])
}

async function send() {
  if (sending.value) return
  if (compactionInProgress.value) {
    ElMessage.warning('上下文正在压缩，请等待压缩结果')
    return
  }
  const text = prompt.value
  if (!text.trim() && !drafts.value.length) return
  if (!s.currentSessionId) {
    ElMessage.warning('请先创建编码环境和会话')
    return
  }
  const sessionId = s.currentSessionId
  const attachmentItems = drafts.value.map((item) => ({ ...item }))
  sending.value = true
  autoFollowConversation.value = true
  scrollMessagesToBottom()
  try {
    const result = await api('message.send', { text })
    prompt.value = ''
    const consumed = consumeMessageTask({
      ...result,
      type: 'message_task',
      id: result?.id,
      session_id: result?.session_id || sessionId,
      status: result?.status || 'pending',
      content: result?.content ?? text,
      attachments: result?.attachments,
      attachment_items: result?.attachment_items || attachmentItems,
    })
    if (!consumed) await loadMessageTasks(sessionId)
  } catch (error) {
    ElMessage.error(error?.message || String(error) || '发送失败')
  } finally {
    sending.value = false
  }
}

const polishAvailable = computed(() => !!s.polishEnabled)
const polishAgentName = computed(() => s.polishAgentName || '')
const polishTitle = computed(() => `润色输入内容（${polishAgentName.value}）`)

async function startPolish() {
  const text = prompt.value.trim()
  if (!text || polishVisible.value) return
  polishStatus.value = 'running'
  polishError.value = ''
  polishResults.value = []
  polishActive.value = 0
  polishTaskId.value = ''
  polishVisible.value = true
  try {
    const { taskId } = await api('polish.start', { text })
    polishTaskId.value = taskId
    schedulePolishPoll()
  } catch (error) {
    polishStatus.value = 'failed'
    polishError.value = error?.message || String(error)
  }
}

function schedulePolishPoll() {
  clearPolishPoll()
  polishPollTimer = setTimeout(pollPolish, POLISH_POLL_INTERVAL)
}

function clearPolishPoll() {
  if (polishPollTimer) {
    clearTimeout(polishPollTimer)
    polishPollTimer = null
  }
}

async function pollPolish() {
  const taskId = polishTaskId.value
  if (!taskId) return
  try {
    const data = await api('polish.poll', { taskId })
    if (polishTaskId.value !== taskId) return
    if (data.status === 'running') {
      schedulePolishPoll()
      return
    }
    polishStatus.value = data.status
    polishTaskId.value = ''
    if (data.status === 'completed') polishResults.value = data.results || []
    else if (data.status === 'failed') polishError.value = data.error || '润色失败'
  } catch (error) {
    if (polishTaskId.value !== taskId) return
    polishStatus.value = 'failed'
    polishError.value = error?.message || String(error)
  }
}

// 关闭润色弹窗。仍在运行的任务需要中断；已出结果或已失败的任务宿主侧已回收，无需再取消。
// 幂等：弹窗 @close 与按钮点击可能先后触发同一次关闭。
async function closePolish() {
  const taskId = polishTaskId.value
  const running = polishStatus.value === 'running'
  clearPolishPoll()
  polishTaskId.value = ''
  polishVisible.value = false
  if (taskId && running) await invoke('polish.cancel', { taskId })
}

// 当前选中方案的完整文本；结果为空时返回空串，避免模板越界访问。
const polishActiveText = computed(() => polishResults.value[polishActive.value] || '')

function applyPolishResult() {
  const text = polishActiveText.value
  if (!text) return
  prompt.value = text
  closePolish()
}

onMounted(() => document.addEventListener('pointerdown', handleSettingsOutsidePointerDown, true))
onUnmounted(() => {
  document.removeEventListener('pointerdown', handleSettingsOutsidePointerDown, true)
  clearPolishPoll()
})


function openAppendDialog(item) {
  if (!item || item.status !== 'processing') return
  appendTarget.value = item
  appendPrompt.value = ''
  appendAttachments.value = []
  appendDialogVisible.value = true
}

function mergeAppendAttachments(items) {
  const merged = new Map(appendAttachments.value.map((item) => [item.path || item.id, item]))
  ;(items || []).forEach((item) => merged.set(item.path || item.id, item))
  appendAttachments.value = [...merged.values()]
}

async function chooseAppendAttachments() {
  mergeAppendAttachments(await api('appendAttachment.choose'))
}

function pasteAppendAttachments(event) {
  event.preventDefault()
  event.stopImmediatePropagation()
  const text = String(event?.clipboardData?.getData('text') || '')
  window.setTimeout(() => {
    api('appendAttachment.paste').then((items) => {
      const attachments = Array.isArray(items) ? items : (items?.attachments || [])
      if (attachments.length) {
        mergeAppendAttachments(attachments)
        return
      }
      if (text) appendPrompt.value = `${appendPrompt.value}${appendPrompt.value ? '\n' : ''}${text}`
    }).catch((error) => {
      ElMessage.error(error?.message || String(error) || '粘贴附件失败')
    })
  }, 0)
}

function removeAppendAttachment(id) {
  appendAttachments.value = appendAttachments.value.filter((item) => item.id !== id)
}

function closeAppendDialog() {
  appendTarget.value = null
  appendPrompt.value = ''
  appendAttachments.value = []
}

async function saveAppendMessage() {
  const text = appendPrompt.value.trim()
  if ((!text && !appendAttachments.value.length) || !appendTarget.value || appendSaving.value) return
  appendSaving.value = true
  try {
    await api('queue.append', {
      turnId: appendTarget.value.turn_id || appendTarget.value.turnId,
      text,
      attachments: appendAttachments.value.map((item) => item.id),
    })
    appendDialogVisible.value = false
    closeAppendDialog()
  } finally {
    appendSaving.value = false
  }
}

function stopQueueItem(item) {
  invoke('queue.stop', { id: item.id })
}

function queuePreview(value) {
  const characters = [...String(value || '')]
  return characters.length > 5 ? `${characters.slice(0, 5).join('')}…` : characters.join('')
}

function openQueueEdit(item) {
  if (!item.editable) return
  queueEditItem.value = item
  queueEditPrompt.value = item.prompt || ''
  queueEditVisible.value = true
}

async function saveQueueEdit() {
  const text = queueEditPrompt.value.trim()
  if (!text || !queueEditItem.value || queueEditSaving.value) return
  queueEditSaving.value = true
  try {
    await invoke('queue.edit', { id: queueEditItem.value.id, text })
    queueEditVisible.value = false
  } finally {
    queueEditSaving.value = false
  }
}


function openSessionDialog() {
 sessionName.value = ''
 sessionType.value = 'project'
 sessionDialogVisible.value = true
}

async function createSession() {
  const name = sessionName.value.trim()
  if (!name || creatingSession.value) return
  creatingSession.value = true
  try {
    await invoke('session.new', { name, sessionType: sessionType.value })
    sessionDialogVisible.value = false
  } catch (error) {
    ElMessage.error(error?.message || String(error) || '创建会话失败')
  } finally {
    creatingSession.value = false
  }
}

function open(page) {
  invoke('window.open', { text: page })
}

watch(() => s.currentSessionId, (sessionId) => {
  loadMessageTasks(sessionId).catch(() => {})
}, { immediate: true })

async function refreshCache() {
  try {
    await invoke('cache.refresh')
    await loadMessageTasks()
  } catch (error) {
    ElMessage.error(error?.message || String(error) || '刷新失败')
  }
}

function clear() {
  ElMessageBox.confirm('确定清空当前会话的全部对话记录？', '清空会话', { type: 'warning' })
    .then(() => invoke('session.clear'))
    .catch(() => {})
}

function paste(event) {
  const hasFiles = [...event.clipboardData.items].some((item) => item.kind === 'file')
  if (!hasFiles) return
  event.preventDefault()
  invoke('attachment.paste')
}

function drop(event) {
  dragging.value = false
  event.preventDefault()
}
</script>

<template>
  <main
    class="chat"
    :class="{ dragging }"
    @dragenter.prevent="dragging = true"
    @dragover.prevent
    @dragleave.self="dragging = false"
    @drop="drop"
  >
    <header class="chat-top">
      <el-select :model-value="s.currentSessionId" @change="id => invoke('session.select', { id })">
        <el-option v-for="item in s.sessions" :key="item.id" :value="item.id" :label="`${item.sessionType === 'global' ? '[全局]' : '[项目]'} ${item.name}`" />
      </el-select>
      <el-button :icon="Plus" text @click="openSessionDialog" />
      <el-button :icon="Refresh" text title="刷新会话" @click="refreshCache" />
      <el-button :icon="Delete" text title="清空会话" @click="clear" />
      <el-button text title="会话管理" @click="open('sessions')">会话</el-button>
      <el-popover :visible="moreMenuVisible" placement="bottom-end" :width="168" trigger="manual" popper-class="chat-more-popper">
        <template #reference>
          <el-button text :icon="MoreFilled" title="更多" @click="moreMenuVisible = !moreMenuVisible" />
        </template>
        <div class="chat-more-menu">
          <button type="button" @click="moreMenuVisible = false; open('catalog')">模型管理</button>
          <button type="button" @click="moreMenuVisible = false; open('agents')">Agent</button>
          <button type="button" @click="moreMenuVisible = false; open('prompts')">提示词</button>
          <button type="button" @click="moreMenuVisible = false; open('skills')">技能</button>
          <button type="button" @click="moreMenuVisible = false; open('environments')">环境管理</button>
          <button type="button" @click="moreMenuVisible = false; open('settings')">设置</button>
          <button type="button" @click="moreMenuVisible = false; open('logs')">日志</button>
        </div>
      </el-popover>
    </header>

    <section ref="messageList" class="message-list" @scroll="handleConversationScroll">
      <el-empty v-if="!s.currentSessionId" :image-size="72" description="请先在右上角创建会话。如果还没有编码环境，请打开「环境管理」新建一个。" />
      <el-empty v-else-if="!events.length" :image-size="72" description="开始一段新的对话" />
      <ConversationTimeline
        v-else
        :events="events"
        :session-id="s.currentSessionId"
        :message-tasks="tasks"
        :attachment-url="eventAttachmentUrl"
        :load-event-detail="loadEventDetail"
        @delete-turn="deleteTurn"
        @event-loaded="onEventLoaded"
      />
    </section>

    <footer class="composer">
                  <div v-if="drafts.length || fileContextChip" class="draft-strip">
        <el-tooltip
          v-if="fileContextChip"
          :content="fileContextChipTooltip"
          placement="top"
          :show-after="200"
          :hide-after="0"
          popper-class="file-context-tooltip"
          raw-content
        >
          <div class="file-ref-chip">
            <span class="file-ref-icon" aria-hidden="true">📄</span>
            <b>{{ fileContextChip.label }}</b>
            <el-button
              :icon="Close"
              text
              aria-label="关闭文件上下文"
              @click.stop="closeFileContextChip"
            />
          </div>
        </el-tooltip>
<div v-for="item in drafts" :key="item.id" class="draft-chip">
          <span v-if="isImageAttachment(item) && draftPreviewSrc(item)" class="draft-thumb" @click="openAttachment(item)"><img :src="draftPreviewSrc(item)" :alt="item.name"/></span>
          <span v-else class="draft-icon" @click="openAttachment(item)">▤</span>
          <div @click="openAttachment(item)">
            <b>{{ item.name }}</b>
            <small>{{ item.mimeType || item.kind }} · {{ Math.max(1, Math.ceil(item.size / 1024)) }} KB</small>
          </div>
          <el-button :icon="Close" text @click.stop="invoke('attachment.remove', { id: item.id })" />
        </div>
      </div>

      <MessageTaskPanel
        v-if="s.currentSessionId"
        :tasks="tasks"
        :session-id="s.currentSessionId"
        :upload-attachments="uploadTaskAttachments"
        :paste-attachments="pasteTaskAttachments"
        :attachment-url="eventAttachmentUrl"
        :is-image-attachment="isImageAttachment"
        @cancel-processing="cancelProcessing"
        @cancel-queued="cancelQueued"
        @update-queued="updateQueued"
        @append-request="openAppendDialog"
        @preview="previewAttachment"
      />
      <div class="composer-box">
        <div class="composer-input" :class="{ expanded: composerExpanded }">
          <el-button
            class="composer-expand"
            :icon="composerExpanded ? ArrowDown : ArrowUp"
            circle
            plain
            :title="composerExpanded ? '收起输入框' : '展开输入框'"
            @click="composerExpanded = !composerExpanded"
          />
          <el-input
            v-model="prompt"
            type="textarea"
            resize="none"
            :rows="composerExpanded ? 10 : 4"
            placeholder="输入消息…"
            @paste="paste"
            @keydown.meta.enter.prevent="send"
            @keydown.ctrl.enter.prevent="send"
          />
        </div>
        <div class="composer-foot">
          <div class="model-controls">
            <el-button circle size="small" :icon="Paperclip" title="添加附件" @click="invoke('attachment.choose')" />
            <el-tooltip
              :content="fileContextTitleHtml"
              placement="top"
              :show-after="200"
              :hide-after="0"
              popper-class="file-context-tooltip"
              raw-content
            >
              <el-button
                circle
                size="small"
                text
                class="file-context-btn"
                :class="{ active: fileContextEnabled }"
                :type="fileContextEnabled ? 'primary' : 'default'"
                aria-label="文件上下文"
                @click="toggleFileContext"
              >
                <span class="file-context-icon" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <g stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M4.35 2.25h5.05L11.65 4.5v9.25H4.35c-.75 0-1.35-.6-1.35-1.35V3.6c0-.75.6-1.35 1.35-1.35Z"/>
                      <path d="M9.25 2.35V4.8h2.3"/>
                      <path d="M6.05 7.55 4.95 8.85 6.05 10.15"/>
                      <path d="M9.95 7.55 11.05 8.85 9.95 10.15"/>
                      <path d="M8.55 7.2 7.45 10.5"/>
                    </g>
                  </svg>
                </span>
              </el-button>
            </el-tooltip>
            <el-popover :visible="modelSettingsPopoverVisible" title="模型设置" placement="top-start" :width="300" trigger="manual" popper-class="coding-settings-popper">
              <template #reference>
                <el-button class="model-settings-trigger" text :icon="Setting" :title="modelSettingsLabel" @click="openModelSettings">
                  <span>{{ modelSettingsLabel }}</span>
                </el-button>
              </template>
              <div ref="modelSettingsMenu" class="model-settings-menu">
                <label>编码环境
                  <el-select :model-value="s.currentEnvironmentId" placeholder="请先创建编码环境" @change="id => invoke('environment.select', { id })" @visible-change="handleSettingsSelectVisible">
                    <el-option v-for="item in environments" :key="item.id" :value="item.id" :label="item.name" />
                  </el-select>
                </label>
                <label>供应商
                  <el-select v-model="selectedProviderId" @change="handleProviderChange" @visible-change="handleSettingsSelectVisible">
                    <el-option v-for="item in providers" :key="item.id" :value="item.id" :label="item.name" />
                  </el-select>
                </label>
                <label>模型
                  <el-select v-model="selectedModelId" @change="handleActiveModelChange" @visible-change="handleSettingsSelectVisible">
                    <el-option v-for="item in availableModels" :key="item.id" :value="item.id" :label="item.display_name || item.alias" />
                  </el-select>
                </label>
                <label>提示词
                  <el-select v-model="selectedPromptId" clearable placeholder="不使用额外提示词" @visible-change="handleSettingsSelectVisible">
                    <el-option v-for="item in prompts" :key="item.id" :value="item.id" :label="item.name" />
                  </el-select>
                </label>
                <div class="reasoning-settings-actions">
                  <el-button size="small" @click="openAdvancedParams">高级参数</el-button>
                  <el-button size="small" type="primary" :disabled="!selectedModelId" @click="saveModelSettings">完成</el-button>
                </div>
              </div>
            </el-popover>
            <el-popover :visible="reasoningPopoverVisible" title="推理设置" placement="top-start" :width="300" trigger="manual" popper-class="coding-settings-popper">
              <template #reference>
                <el-button class="reasoning-settings-trigger" text :icon="Cpu" :title="reasoningSettingsLabel" @click="openReasoningSettings">
                  <span>{{ reasoningSettingsLabel }}</span>
                </el-button>
              </template>
              <div ref="reasoningSettingsMenu" class="model-settings-menu reasoning-settings-menu">
                <template v-if="activeProvider?.kind === 'anthropic'">
                  <label>思考
                    <el-select v-model="activeThinkingType" clearable placeholder="继承模型" @visible-change="handleSettingsSelectVisible">
                      <el-option label="关闭" value="disabled" />
                      <el-option label="开启" value="enabled" />
                      <el-option label="自适应" value="adaptive" />
                    </el-select>
                  </label>
                  <label>推理级别
                    <el-select v-model="reasoningLevel" clearable placeholder="继承模型" @visible-change="handleSettingsSelectVisible">
                      <el-option v-for="option in reasoningOptions" :key="option.value" :label="option.label" :value="option.value" />
                    </el-select>
                  </label>
                  <label v-if="['enabled','adaptive'].includes(reasoningConfig?.type)">Token 预算
                    <el-input-number v-model="reasoningConfig.budget_tokens" :min="1024" :step="1024" controls-position="right" />
                  </label>
                  <label v-if="['enabled','adaptive'].includes(reasoningConfig?.type)">展示
                    <el-select v-model="reasoningConfig.display" placeholder="选择展示方式" @visible-change="handleSettingsSelectVisible">
                      <el-option label="摘要" value="summarized" />
                      <el-option label="省略" value="omitted" />
                    </el-select>
                  </label>
                </template>
                <label v-else>推理强度
                  <el-select v-model="reasoningLevel" clearable placeholder="继承模型设置" @visible-change="handleSettingsSelectVisible">
                    <el-option v-for="option in reasoningOptions" :key="option.value" :label="option.label" :value="option.value" />
                  </el-select>
                </label>
                <div class="reasoning-settings-actions">
                  <el-button size="small" @click="openAdvancedParams">高级参数</el-button>
                  <el-button size="small" type="primary" @click="saveReasoningSettings">完成</el-button>
                </div>
              </div>
            </el-popover>
          </div>
          <div class="send-controls">
            <el-button
              v-if="polishAvailable"
              size="small"
              :icon="MagicStick"
              :disabled="sending || compactionInProgress || !prompt.trim()"
              :title="compactionInProgress ? '上下文正在压缩' : `使用 ${polishAgentName} 润色输入内容`"
              @click="startPolish"
            >润色</el-button>
            <el-button
              type="primary"
              size="small"
              :loading="sending || compactionInProgress"
              :disabled="sending || compactionInProgress || (!prompt.trim() && !drafts.length)"
              :title="compactionInProgress ? '上下文正在压缩，请等待压缩结果' : '发送'"
              @click="send"
            >发送</el-button>
          </div>
        </div>
      </div>
      <div v-if="events.length" class="conversation-metrics">
        <span>{{ sessionMetrics.turns }} 轮 · {{ sessionMetrics.blocks }} 步</span>
        <span>总耗时 {{ sessionMetrics.duration }} · 工具 {{ sessionMetrics.tools }} 次</span>
        <button class="context-metric" type="button" :disabled="!s.currentSessionId" @click="openContextDialog">
          上下文 {{ contextHint }}
        </button>
        <el-tooltip v-if="sessionMetrics.total != null || sessionMetrics.usageRows.length" placement="top">
          <template #content>
            <div class="token-usage-tooltip">
              <strong>Token 用量</strong>
              <span v-for="row in sessionMetrics.usageRows" :key="row.key"><span>{{ row.key }}</span><b>{{ row.value }}</b></span>
            </div>
          </template>
          <span class="token-summary">{{ sessionMetrics.total != null ? `${Number(sessionMetrics.total).toLocaleString()} tok` : '用量' }}</span>
        </el-tooltip>
      </div>
    </footer>

    <div v-if="dragging" class="drop-overlay"><div>释放文件以添加附件</div></div>

    <el-image-viewer v-if="previewVisible" :url-list="[previewSrc]" @close="previewVisible=false"/>
  <el-dialog
    v-model="appendDialogVisible"
    class="append-message-dialog"
    title="追加消息"
    width="560px"
    :append-to-body="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    align-center
    @closed="closeAppendDialog"
  >
    <el-input
      v-model="appendPrompt"
      type="textarea"
      :rows="6"
      resize="none"
      maxlength="200000"
      show-word-limit
      autofocus
      placeholder="输入要在本轮对话中优先投递的消息；也可以只追加附件"
      @paste="pasteAppendAttachments"
      @keydown.meta.enter.prevent="saveAppendMessage"
      @keydown.ctrl.enter.prevent="saveAppendMessage"
    />
    <div class="append-attachment-toolbar">
      <el-button :icon="Paperclip" @click="chooseAppendAttachments">添加附件</el-button>
      <span>支持图片或文件，可只追加附件</span>
    </div>
    <div v-if="appendAttachments.length" class="append-attachment-list">
      <div v-for="item in appendAttachments" :key="item.id" class="append-attachment-chip">
        <span v-if="item.kind==='image'&&item.previewUrl" class="draft-thumb" @click="openAttachment(item)"><img :src="item.previewUrl" :alt="item.name"/></span>
        <span v-else class="draft-icon" @click="openAttachment(item)">▤</span>
        <div @click="openAttachment(item)"><b>{{item.name}}</b><small>{{item.mimeType||item.kind}} · {{Math.max(1,Math.ceil(item.size/1024))}} KB</small></div>
        <el-button :icon="Close" text aria-label="移除追加附件" @click="removeAppendAttachment(item.id)" />
      </div>
    </div>
    <template #footer>
      <el-button @click="appendDialogVisible=false">取消</el-button>
      <el-button type="primary" :disabled="!appendPrompt.trim()&&!appendAttachments.length" :loading="appendSaving" @click="saveAppendMessage">追加</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="queueEditVisible" title="编辑排队消息" width="560px" append-to-body>
    <el-input
      v-model="queueEditPrompt"
      type="textarea"
      :rows="8"
      resize="none"
      maxlength="200000"
      show-word-limit
      autofocus
      @keydown.meta.enter.prevent="saveQueueEdit"
      @keydown.ctrl.enter.prevent="saveQueueEdit"
    />
    <template #footer>
      <el-button @click="queueEditVisible=false">取消</el-button>
      <el-button type="primary" :disabled="!queueEditPrompt.trim()" :loading="queueEditSaving" @click="saveQueueEdit">保存</el-button>
    </template>
  </el-dialog>
  <el-dialog
    v-model="polishVisible"
    :title="polishTitle"
    width="760px"
    class="polish-dialog"
    append-to-body
    :close-on-click-modal="false"
    @close="closePolish"
  >
    <div v-if="polishStatus==='running'" class="polish-running">
      <el-icon class="is-loading"><Refresh /></el-icon>
      <span>润色中，请稍候…</span>
    </div>
    <el-alert v-else-if="polishStatus==='failed'" type="error" :closable="false" :title="polishError" />
    <div v-else-if="polishStatus==='completed'" class="polish-body">
      <el-tabs v-model="polishActive" class="polish-tabs">
        <el-tab-pane v-for="(_, index) in polishResults" :key="index" :name="index" :label="`方案 ${index + 1}`" />
      </el-tabs>
      <pre class="polish-preview">{{ polishActiveText }}</pre>
    </div>
    <template #footer>
      <span v-if="polishStatus==='completed'" class="polish-hint">{{ polishActiveText.length }} 字符</span>
      <el-button @click="closePolish">{{ polishStatus==='running' ? '中断' : '关闭' }}</el-button>
      <el-button
        v-if="polishStatus==='completed'"
        type="primary"
        :disabled="!polishActiveText"
        @click="applyPolishResult"
      >应用到输入框</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="contextVisible" title="上下文管理" width="560px" append-to-body>
    <div class="context-overview">
      <el-progress type="dashboard" :percentage="Number(context.percent || 0)" :format="formatContextPercent" :width="120" />
      <div>
        <h3>{{ Number(context.estimated_tokens || 0).toLocaleString() }} tokens</h3>
        <p>下一轮模型请求的有效上下文估算</p>
        <p v-if="!context.compaction_agent_configured" class="context-warning">未配置压缩 Agent，无法手动压缩。</p>
        <p v-else-if="sessionRunning" class="context-warning">会话运行中，请先等待当前任务结束。</p>
      </div>
    </div>
    <template #footer>
      <el-button @click="contextVisible = false">关闭</el-button>
      <el-button
        type="primary"
        :loading="compactionInProgress"
        :disabled="!context.compaction_agent_configured || sessionRunning || compactionInProgress"
        @click="compactContext"
      >压缩上下文</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="advancedParamsVisible" title="高级参数" width="860px" append-to-body :close-on-click-modal="false">
    <el-form label-position="top" class="advanced-params-form">
      <ModelParamsForm
        v-model:additional-params-text="sessionModelParams.additional_params_text"
        :provider-kind="activeProvider?.kind || currentSession?.model_snapshot?.provider_kind || ''"
        :api-type="codingEffectiveApi"
        :model-params="sessionModelParams.model_params"
        :execution-params="sessionModelParams.execution_params"
        :openai-compatible="selectedOpenAiCompatible"
        :session-params="sessionModelParams"
        show-session-runtime
        hide-reasoning-fields
        catalog-aligned
      />
    </el-form>
    <template #footer>
      <el-button @click="advancedParamsVisible = false">取消</el-button>
      <el-button type="primary" :loading="advancedParamsSaving" :disabled="!selectedModelId" @click="saveAdvancedParams">保存</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="previewVisible" title="图片预览" width="min(900px, 92vw)" append-to-body>
    <img v-if="previewSrc" :src="previewSrc" alt="preview" style="display:block;width:100%;max-height:80vh;object-fit:contain" />
  </el-dialog>
  <el-dialog v-model="sessionDialogVisible" title="新建会话" width="440px" append-to-body>
  <el-form label-position="top" @submit.prevent="createSession">
   <el-form-item label="会话名称" required>
    <el-input v-model="sessionName" maxlength="80" show-word-limit autofocus @keyup.enter="createSession" />
   </el-form-item>
   <el-form-item label="会话范围" required>
    <el-radio-group v-model="sessionType" class="session-type-options">
     <el-radio value="project"><div><strong>项目会话</strong><small>仅在当前项目中可见</small></div></el-radio>
     <el-radio value="global"><div><strong>全局会话</strong><small>所有项目共享，可自由切换</small></div></el-radio>
    </el-radio-group>
   </el-form-item>
  </el-form>
  <template #footer><el-button @click="sessionDialogVisible=false">取消</el-button><el-button type="primary" :disabled="!sessionName.trim()" :loading="creatingSession" @click="createSession">创建</el-button></template>
 </el-dialog>
 </main>
</template>
