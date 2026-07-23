<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, Close, Delete, EditPen, Paperclip, Plus, Refresh, VideoPause } from '@element-plus/icons-vue'
import { hostState as s, invoke } from '../bridge/jcefBridge'
import ChatMessage from '../components/chat/ChatMessage.vue'

const prompt = ref('')
const messageList = ref(null)
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
const MESSAGE_CARD_POOL_SIZE = 10
const MESSAGE_CACHE_PREFIX = 'jtools:chat-messages:v1:'
const SELECTED_SESSION_CACHE_KEY = 'jtools:selected-session:v1'
const MESSAGE_CACHE_LIMIT = 200
const MESSAGE_CACHE_JSON_LIMIT = 2 * 1000 * 1000
const RECYCLE_EDGE_THRESHOLD = 72
const messageWindowStart = ref(0)
const recyclerAdjusting = ref(false)
const drafts = computed(() => s.drafts || [])
const fileContextEnabled = computed(() => !!s.fileContextEnabled)
const fileContextLabel = computed(() => s.fileContextLabel || '文件上下文')
const fileContextChip = computed(() => (fileContextEnabled.value ? s.fileContextChip : null) || null)
const fileContextTitle = computed(() => {
  if (!fileContextEnabled.value) {
    return '开启文件上下文（仅传递路径，不传文件内容）'
  }
  const detail = s.fileContextChip?.tooltip || s.fileContextChip?.label
  return detail
    ? `关闭文件上下文\n${detail}`
    : '关闭文件上下文（仅传递路径，不传文件内容）'
})
const fileContextChipTooltip = computed(() => escapeTooltipHtml(fileContextChip.value?.tooltip || fileContextChip.value?.label || ''))
const fileContextTitleHtml = computed(() => escapeTooltipHtml(fileContextTitle.value))
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
const queue = computed(() => s.queue || [])
const messageWindowMaxStart = computed(() => Math.max(0, s.messages.length - MESSAGE_CARD_POOL_SIZE))
const visibleMessages = computed(() => s.messages.slice(
  messageWindowStart.value,
  Math.min(s.messages.length, messageWindowStart.value + MESSAGE_CARD_POOL_SIZE)
))
const previewSrc = ref('')
const previewVisible = ref(false)
function openAttachment(att) {
  if (att.kind === 'image' && att.previewUrl) {
    previewSrc.value = att.previewUrl
    previewVisible.value = true
  } else {
    invoke('attachment.open', { text: att.path })
  }
}

watch(
  () => s.historyRevision,
  (revision, previous) => {
    if (!revision || revision === previous) return
    persistMessages(s.currentSessionId, s.messages)
    messageWindowStart.value = messageWindowMaxStart.value
    scrollMessagesToBottom()
  },
  { flush: 'post' }
)

watch(
  () => s.inputRestore?.sequence,
  (sequence) => {
    if (sequence) prompt.value = s.inputRestore.text || ''
  }
)

watch(
  () => [s.currentSessionId, s.messages.length],
  ([sessionId, length], previous = []) => {
    const sessionChanged = sessionId !== previous[0]
    const appended = !sessionChanged && length > (previous[1] || 0)
    persistSelectedSession(sessionId)
    persistMessages(sessionId, s.messages)
    if (sessionChanged || appended || messageWindowStart.value > messageWindowMaxStart.value) {
      messageWindowStart.value = messageWindowMaxStart.value
      scrollMessagesToBottom()
    }
  },
  { flush: 'post' }
)

watch(
  () => s.messages.map((item) => [
    item.id,
    item.content,
    item.reasoning,
    item.generating,
    item.actorLabel,
    item.tools?.map((tool) => `${tool.id}:${tool.finished}:${tool.failed}`).join('|')
  ]),
  () => {
    persistMessages(s.currentSessionId, s.messages)
    if (messageWindowStart.value === messageWindowMaxStart.value) scrollMessagesToBottom()
  },
  { deep: true, flush: 'post' }
)

function persistSelectedSession(sessionId) {
  try {
    if (sessionId) localStorage.setItem(SELECTED_SESSION_CACHE_KEY, String(sessionId))
    else localStorage.removeItem(SELECTED_SESSION_CACHE_KEY)
  } catch {
    // Browser cache is only an initial rendering optimization.
  }
}

function messageCacheKey(sessionId) {
  return `${MESSAGE_CACHE_PREFIX}${sessionId}`
}

function normalizeCachedMessages(value) {
  if (!Array.isArray(value)) return []
  const values = new Map()
  value.forEach((item) => {
    if (item?.id && item.persisted === true) values.set(String(item.id), item)
  })
  return [...values.values()]
}

function persistMessages(sessionId, messages) {
  if (!sessionId) return
  try {
    let cached = normalizeCachedMessages(messages).slice(-MESSAGE_CACHE_LIMIT)
    let text = JSON.stringify(cached)
    while (cached.length > 1 && text.length > MESSAGE_CACHE_JSON_LIMIT) {
      cached = cached.slice(Math.max(1, Math.floor(cached.length / 4)))
      text = JSON.stringify(cached)
    }
    if (!cached.length) localStorage.removeItem(messageCacheKey(sessionId))
    else if (text.length <= MESSAGE_CACHE_JSON_LIMIT) localStorage.setItem(messageCacheKey(sessionId), text)
  } catch {
    // Browser cache only accelerates display and is not the conversation source of truth.
  }
}

function scrollMessagesToBottom() {
  nextTick(() => requestAnimationFrame(() => {
    const element = messageList.value
    if (element) element.scrollTop = element.scrollHeight
  }))
}

async function preserveMessageAnchor(anchorId, mutate) {
  const element = messageList.value
  const selector = `[data-message-id="${CSS.escape(String(anchorId || ''))}"]`
  const before = element?.querySelector(selector)?.getBoundingClientRect().top
  recyclerAdjusting.value = true
  mutate()
  await nextTick()
  const after = element?.querySelector(selector)?.getBoundingClientRect().top
  if (element && before != null && after != null) element.scrollTop += after - before
  requestAnimationFrame(() => { recyclerAdjusting.value = false })
}

function moveMessageWindow(step) {
  const next = Math.max(0, Math.min(messageWindowMaxStart.value, messageWindowStart.value + step))
  if (next === messageWindowStart.value) return
  const anchorId = visibleMessages.value[step < 0 ? 0 : Math.min(1, visibleMessages.value.length - 1)]?.id
  preserveMessageAnchor(anchorId, () => { messageWindowStart.value = next })
}

function handleMessageScroll() {
  if (recyclerAdjusting.value) return
  const element = messageList.value
  if (!element) return
  if (element.scrollTop <= RECYCLE_EDGE_THRESHOLD && messageWindowStart.value > 0) moveMessageWindow(-1)
  else if (element.scrollHeight - element.scrollTop - element.clientHeight <= RECYCLE_EDGE_THRESHOLD && messageWindowStart.value < messageWindowMaxStart.value) moveMessageWindow(1)
}

function handleMessageWheel(event) {
  const element = messageList.value
  if (!element || recyclerAdjusting.value) return
  if (event.deltaY < 0 && element.scrollTop <= RECYCLE_EDGE_THRESHOLD && messageWindowStart.value > 0) {
    event.preventDefault()
    moveMessageWindow(-1)
  } else if (event.deltaY > 0 && element.scrollHeight - element.scrollTop - element.clientHeight <= RECYCLE_EDGE_THRESHOLD && messageWindowStart.value < messageWindowMaxStart.value) {
    event.preventDefault()
    moveMessageWindow(1)
  }
}


async function send() {
  if (sending.value) return
  const text = prompt.value
  if (!text.trim() && !drafts.value.length) return
  sending.value = true
  try {
    await invoke('message.send', { text })
    prompt.value = ''
  } finally {
    sending.value = false
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
 } finally {
  creatingSession.value = false
 }
}

function open(page) {
  invoke('window.open', { text: page })
}

async function refreshCache() {
  try {
    const sessionId = s.currentSessionId
    localStorage.removeItem(messageCacheKey(sessionId))
    await invoke('cache.refresh')
  } catch {
    // The host reports refresh failures through the existing command channel.
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
      <span>会话：</span>
      <el-select :model-value="s.currentSessionId" @change="id => invoke('session.select', { id })">
        <el-option v-for="item in s.sessions" :key="item.id" :value="item.id" :label="`${item.sessionType === 'global' ? '[全局]' : '[项目]'} ${item.name}`" />
      </el-select>
      <el-button :icon="Plus" text @click="openSessionDialog" />
      <el-button :icon="Refresh" text title="刷新消息缓存" @click="refreshCache" />
 <el-button :icon="Delete" text @click="clear" />
      <el-button text @click="open('sessions')">管理</el-button>
      <el-button text @click="open('logs')">日志</el-button>
    </header>

    <section ref="messageList" class="message-list" @scroll.passive="handleMessageScroll" @wheel="handleMessageWheel">
      <el-empty v-if="!s.messages.length" description="开始一段新的对话" />
      <ChatMessage v-for="item in visibleMessages" :key="item.id" :data-message-id="item.id" :item="item" />
    </section>

    <footer class="composer">
      <section v-if="queue.length" class="queue-panel" aria-label="消息队列">
        <div class="queue-strip">
          <article
            v-for="item in queue"
            :key="item.id"
            class="queue-item"
            :class="{ processing: item.processing }"
          >
            <span class="queue-state">{{ item.processing ? '进行中' : '排队中' }}</span>
            <div class="queue-copy">
              <strong :title="item.title">{{ queuePreview(item.title) }}</strong>
              <small :title="item.prompt || '附件消息'">{{ queuePreview(item.prompt || '附件消息') }}</small>
            </div>
            <div class="queue-actions">
              <el-button
                v-if="item.editable"
                :icon="EditPen"
                text
                circle
                size="small"
                title="编辑排队消息"
                aria-label="编辑排队消息"
                @click="openQueueEdit(item)"
              />
              <el-button
                :icon="item.processing ? VideoPause : Close"
                :type="item.processing ? 'danger' : 'default'"
                text
                circle
                size="small"
                :title="item.processing ? '停止消息' : '取消排队消息'"
                :aria-label="item.processing ? '停止消息' : '取消排队消息'"
                @click="stopQueueItem(item)"
              />
            </div>
          </article>
        </div>
      </section>

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
            <span class="file-ref-icon" aria-hidden="true">📎</span>
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
          <span v-if="item.kind==='image'&&item.previewUrl" class="draft-thumb" @click="openAttachment(item)"><img :src="item.previewUrl" :alt="item.name"/></span>
          <span v-else class="draft-icon" @click="openAttachment(item)">▤</span>
          <div @click="openAttachment(item)">
            <b>{{ item.name }}</b>
            <small>{{ item.mimeType || item.kind }} · {{ Math.max(1, Math.ceil(item.size / 1024)) }} KB</small>
          </div>
          <el-button :icon="Close" text @click.stop="invoke('attachment.remove', { id: item.id })" />
        </div>
      </div>

      <div class="composer-actions">
        <span class="hint">Cmd/Ctrl+Enter 发送，Enter 换行；支持连续发送进入队列</span>
        <span>Agent：</span>
        <el-select :model-value="s.currentAgentId" @change="id => invoke('agent.select', { id })">
          <el-option v-for="item in s.agents" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
        <el-button :icon="Paperclip" text title="添加附件" @click="invoke('attachment.choose')" />
        <el-tooltip
          :content="fileContextTitleHtml"
          placement="top"
          :show-after="200"
          :hide-after="0"
          popper-class="file-context-tooltip"
          raw-content
        >
          <el-button
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
        <el-button text @click="open('catalog')">模型</el-button>
        <el-button text @click="open('prompts')">提示词</el-button>
        <el-button text @click="open('agents')">Agent</el-button>
        <el-button text @click="open('skills')">技能</el-button>
        <el-button text @click="open('settings')">设置</el-button>
      </div>
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
        <el-button
          class="composer-send"
          type="primary"
          size="small"
          :loading="sending"
          :disabled="sending || (!prompt.trim() && !drafts.length)"
          @click="send"
        >发送</el-button>
      </div>
    </footer>

    <div v-if="dragging" class="drop-overlay"><div>释放文件以添加附件</div></div>

    <el-image-viewer v-if="previewVisible" :url-list="[previewSrc]" @close="previewVisible=false"/>
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
