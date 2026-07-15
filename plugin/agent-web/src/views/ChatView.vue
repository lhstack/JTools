<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Close, Delete, Paperclip, Plus, Refresh, VideoPause } from '@element-plus/icons-vue'
import { hostState as s, invoke } from '../bridge/jcefBridge'
import ChatMessage from '../components/chat/ChatMessage.vue'

const prompt = ref('')
const messageList = ref(null)
const sending = ref(false)
const dragging = ref(false)
const sessionDialogVisible = ref(false)
const sessionName = ref('')
const sessionType = ref('project')
const creatingSession = ref(false)
const MESSAGE_CARD_POOL_SIZE = 10
const MESSAGE_CACHE_PREFIX = 'jtools:chat-messages:v1:'
const SELECTED_SESSION_CACHE_KEY = 'jtools:selected-session:v1'
const MESSAGE_CACHE_LIMIT = 200
const MESSAGE_CACHE_JSON_LIMIT = 2 * 1000 * 1000
const RECYCLE_EDGE_THRESHOLD = 72
const messageWindowStart = ref(0)
const recyclerAdjusting = ref(false)
const drafts = computed(() => s.drafts || [])
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
    if (item?.id) values.set(String(item.id), item)
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
 localStorage.removeItem(messageCacheKey(s.currentSessionId))
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
      <section v-if="queue.length" class="queue-panel">
        <div class="queue-panel-head">
          <strong>消息队列</strong>
          <span>{{ queue.filter(item => item.processing).length }} 条进行中，{{ queue.filter(item => !item.processing).length }} 条排队中</span>
        </div>
        <div class="queue-strip">
          <article
            v-for="item in queue"
            :key="item.id"
            class="queue-item"
            :class="{ processing: item.processing }"
          >
            <span class="queue-state">{{ item.processing ? '进行中' : '排队中' }}</span>
            <div class="queue-copy">
              <strong>{{ item.title }}</strong>
              <small>{{ item.prompt || '附件消息' }}</small>
            </div>
            <el-button
              :icon="item.processing ? VideoPause : Close"
              :type="item.processing ? 'danger' : 'default'"
              size="small"
              plain
              @click="stopQueueItem(item)"
            >
              {{ item.processing ? '停止' : '取消' }}
            </el-button>
          </article>
        </div>
      </section>

      <div v-if="drafts.length" class="draft-strip">
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
        <el-button :icon="Paperclip" text @click="invoke('attachment.choose')" />
        <el-button text @click="open('catalog')">模型</el-button>
        <el-button text @click="open('prompts')">提示词</el-button>
        <el-button text @click="open('agents')">Agent</el-button>
        <el-button text @click="open('skills')">技能</el-button>
        <el-button text @click="open('settings')">设置</el-button>
      </div>
      <el-input
        v-model="prompt"
        type="textarea"
        :rows="4"
        placeholder="输入消息…"
        @paste="paste"
        @keydown.meta.enter.prevent="send"
        @keydown.ctrl.enter.prevent="send"
      />
    </footer>

    <div v-if="dragging" class="drop-overlay"><div>释放文件以添加附件</div></div>

    <el-image-viewer v-if="previewVisible" :url-list="[previewSrc]" @close="previewVisible=false"/>
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
