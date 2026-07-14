<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Close, Delete, Paperclip, Plus, VideoPause } from '@element-plus/icons-vue'
import { hostState as s, invoke } from '../bridge/jcefBridge'
import ChatMessage from '../components/chat/ChatMessage.vue'

const prompt = ref('')
const messageList = ref(null)
const sending = ref(false)
const dragging = ref(false)
const drafts = computed(() => s.drafts || [])
const queue = computed(() => s.queue || [])
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
  () => s.messages.map((item) => [
    item.id,
    item.content,
    item.reasoning,
    item.generating,
    item.tools?.map((tool) => `${tool.id}:${tool.result}:${tool.finished}:${tool.failed}`).join('|')
  ]),
  async () => {
    await nextTick()
    requestAnimationFrame(() => {
      const element = messageList.value
      if (element) element.scrollTop = element.scrollHeight
    })
  },
  { deep: true, flush: 'post' }
)

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

function open(page) {
  invoke('window.open', { text: page })
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
        <el-option v-for="item in s.sessions" :key="item.id" :value="item.id" :label="item.name" />
      </el-select>
      <el-button :icon="Plus" text @click="invoke('session.new')" />
      <el-button :icon="Delete" text @click="clear" />
      <el-button text @click="open('sessions')">管理</el-button>
      <el-button text @click="open('logs')">日志</el-button>
    </header>

    <section ref="messageList" class="message-list">
      <el-empty v-if="!s.messages.length" description="开始一段新的对话" />
      <ChatMessage v-for="item in s.messages" :key="item.id" :item="item" />
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
  </main>
</template>
