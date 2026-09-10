<script setup>
import { computed, reactive, ref } from 'vue'
import { loadAttachmentPreview } from './attachmentUtils.js'
import { ArrowLeft, ArrowRight, ChatDotRound, CircleCloseFilled, Close, Document, Edit, Paperclip } from '@element-plus/icons-vue'

const props = defineProps({
  tasks: { type: Array, default: () => [] },
  sessionId: { type: [Number, String], required: true },
  uploadAttachments: { type: Function, required: true },
  pasteAttachments: { type: Function, default: null },
  attachmentUrl: { type: Function, required: true },
  isImageAttachment: { type: Function, required: true }
})
const emit = defineEmits(['cancel-processing', 'cancel-queued', 'update-queued', 'append-request', 'preview'])
const previewUrls = reactive({})

const queueEditVisible = ref(false)
const queueEditTask = ref(null)
const queueEditContent = ref('')
const queueEditAttachments = ref([])
const queueEditUploading = ref(false)
const attachmentIndexes = ref({})

const sortedTasks = computed(() => [...props.tasks].sort((left, right) => Number(left.id) - Number(right.id)))
const actualProcessingTask = computed(() => sortedTasks.value.find((task) => task.status === 'processing') || null)
const queuedTasks = computed(() => sortedTasks.value.filter((task) => task.status === 'pending'))
const processingTask = computed(() => actualProcessingTask.value)
const pendingTasks = computed(() => queuedTasks.value)
const hasActiveTask = computed(() => Boolean(processingTask.value || pendingTasks.value.length))

function taskSummary(task) {
  const content = String(task?.content || '').replace(/\s+/g, ' ').trim()
  return content || (task?.attachment_items?.length ? `${task.attachment_items.length} 个附件` : '消息')
}
function isPreviewableImage(attachment) {
  const contentType = String(attachment?.content_type || attachment?.contentType || attachment?.mimeType || '').toLowerCase()
  return props.isImageAttachment(attachment) || contentType.startsWith('image/') || String(attachment?.kind || '').toLowerCase() === 'image'
}
function previewSrc(attachment) {
  const key = String(attachment?.id || attachment?.path || '')
  if (!key) return ''
  if (Object.prototype.hasOwnProperty.call(previewUrls, key)) return previewUrls[key]
  const existing = props.attachmentUrl?.(attachment, props.sessionId)
  if (existing) {
    previewUrls[key] = existing
    return existing
  }
  previewUrls[key] = ''
  loadAttachmentPreview(attachment, props.sessionId).then((url) => {
    previewUrls[key] = url || ''
  }).catch(() => {
    previewUrls[key] = ''
  })
  return previewUrls[key]
}

async function preview(attachment) {
  if (!attachment) return
  if (isPreviewableImage(attachment)) {
    const url = previewSrc(attachment) || await loadAttachmentPreview(attachment, props.sessionId)
    emit('preview', { ...attachment, url, previewUrl: url })
    return
  }
  emit('preview', attachment)
}
function taskAttachments(task) {
  return task?.attachment_items || []
}
function attachmentIndex(task) {
  const items = taskAttachments(task)
  if (!items.length) return 0
  const current = Number(attachmentIndexes.value[task.id] || 0)
  return ((current % items.length) + items.length) % items.length
}
function currentAttachment(task) {
  const items = taskAttachments(task)
  return items[attachmentIndex(task)] || null
}
function shiftAttachment(task, delta) {
  const items = taskAttachments(task)
  if (items.length <= 1) return
  attachmentIndexes.value = {
    ...attachmentIndexes.value,
    [task.id]: (attachmentIndex(task) + delta + items.length) % items.length
  }
}
function openQueueEdit(task) {
  queueEditTask.value = task
  queueEditContent.value = task.content || ''
  queueEditAttachments.value = [...(task.attachment_items || [])]
  queueEditVisible.value = true
}
function openAppend() {
  if (!processingTask.value) return
  emit('append-request', processingTask.value)
}
function clipboardText(event) {
  return String(event?.clipboardData?.getData('text') || '')
}
function onQueueEditPaste(event) {
  event.preventDefault()
  event.stopImmediatePropagation()
  const text = clipboardText(event)
  void addQueueAttachments('paste').then((items) => {
    if (items.length || !text) return
    queueEditContent.value = `${queueEditContent.value}${queueEditContent.value ? '\n' : ''}${text}`
  }).catch((error) => console.error(error))
}
async function addQueueAttachments(source) {
  queueEditUploading.value = true
  try {
    const attachments = source === 'paste'
      ? await (props.pasteAttachments || props.uploadAttachments)()
      : await props.uploadAttachments()
    const items = Array.isArray(attachments) ? attachments : (attachments?.attachments || [])
    if (items.length) queueEditAttachments.value.push(...items)
    return items
  } finally {
    queueEditUploading.value = false
  }
}
function removeAttachment(list, id) {
  const index = list.findIndex((item) => Number(item.id) === Number(id))
  if (index >= 0) list.splice(index, 1)
}
function saveQueueEdit() {
  if (!queueEditTask.value) return
  emit('update-queued', {
    task: queueEditTask.value,
    content: queueEditContent.value,
    attachments: queueEditAttachments.value.map((item) => item.id)
  })
  queueEditVisible.value = false
}
</script>

<template>
  <div v-if="hasActiveTask" class="status-strip">
    <div v-if="processingTask" class="processing-bar" :class="{ 'has-attachments': currentAttachment(processingTask), carousel: taskAttachments(processingTask).length > 1 }">
      <span class="queue-status-badge">处理中</span>
      <span class="queue-card-copy">
        <strong>当前消息</strong>
        <span>{{ taskSummary(processingTask) }}</span>
      </span>
      <div v-if="currentAttachment(processingTask)" class="queue-item-attachments" :class="{ carousel: taskAttachments(processingTask).length > 1 }">
        <button v-if="taskAttachments(processingTask).length > 1" type="button" class="queue-attachment-nav" @click="shiftAttachment(processingTask, -1)">
          <el-icon><ArrowLeft /></el-icon>
        </button>
        <button type="button" class="queue-attachment" @click="preview(currentAttachment(processingTask))">
          <img v-if="isPreviewableImage(currentAttachment(processingTask)) && previewSrc(currentAttachment(processingTask))" :src="previewSrc(currentAttachment(processingTask))" :alt="currentAttachment(processingTask).file_name" />
          <Document v-else />
          <span v-if="taskAttachments(processingTask).length > 1" class="queue-attachment-count">{{ attachmentIndex(processingTask) + 1 }}/{{ taskAttachments(processingTask).length }}</span>
        </button>
        <button v-if="taskAttachments(processingTask).length > 1" type="button" class="queue-attachment-nav" @click="shiftAttachment(processingTask, 1)">
          <el-icon><ArrowRight /></el-icon>
        </button>
      </div>
      <div class="processing-actions">
        <el-tooltip content="追加到当前对话轮次" placement="top">
          <el-button class="processing-append-button" circle size="small" type="primary" plain :icon="ChatDotRound" @click="openAppend" />
        </el-tooltip>
        <el-tooltip content="取消：优先取消当前工具批次，否则中断模型回复" placement="top">
          <el-button class="processing-stop-button" circle size="small" type="warning" plain :icon="CircleCloseFilled" @click="emit('cancel-processing', processingTask)" />
        </el-tooltip>
      </div>
    </div>
    <div v-if="pendingTasks.length" class="queue-strip">
      <div v-for="task in pendingTasks" :key="task.id" class="queue-item" :class="{ 'has-attachments': currentAttachment(task), carousel: taskAttachments(task).length > 1 }">
        <span class="queue-status-badge pending">排队</span>
        <span class="queue-card-copy">
          <strong>待处理消息</strong>
          <span>{{ taskSummary(task) }}</span>
        </span>
        <div v-if="currentAttachment(task)" class="queue-item-attachments" :class="{ carousel: taskAttachments(task).length > 1 }">
          <button v-if="taskAttachments(task).length > 1" type="button" class="queue-attachment-nav" @click="shiftAttachment(task, -1)">
            <el-icon><ArrowLeft /></el-icon>
          </button>
          <button type="button" class="queue-attachment" @click="preview(currentAttachment(task))">
            <img v-if="isPreviewableImage(currentAttachment(task)) && previewSrc(currentAttachment(task))" :src="previewSrc(currentAttachment(task))" :alt="currentAttachment(task).file_name" />
            <Document v-else />
            <span v-if="taskAttachments(task).length > 1" class="queue-attachment-count">{{ attachmentIndex(task) + 1 }}/{{ taskAttachments(task).length }}</span>
          </button>
          <button v-if="taskAttachments(task).length > 1" type="button" class="queue-attachment-nav" @click="shiftAttachment(task, 1)">
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="queue-item-actions">
          <el-tooltip content="编辑排队消息" placement="top">
            <el-button circle size="small" :icon="Edit" @click="openQueueEdit(task)" />
          </el-tooltip>
          <el-tooltip content="取消排队" placement="top">
            <el-button class="queue-cancel-button" circle size="small" type="danger" plain :icon="Close" @click="emit('cancel-queued', task)" />
          </el-tooltip>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="queueEditVisible" title="编辑排队消息" width="min(680px, 92vw)" :append-to-body="false" :lock-scroll="false" :close-on-click-modal="false" :close-on-press-escape="false" align-center>
    <el-input v-model="queueEditContent" type="textarea" :rows="5" resize="vertical" @paste="onQueueEditPaste" />
    <el-button :icon="Paperclip" :loading="queueEditUploading" @click="addQueueAttachments('choose')">添加附件</el-button>
    <div v-if="queueEditAttachments.length" class="task-dialog-attachments">
      <div v-for="attachment in queueEditAttachments" :key="attachment.id" class="task-dialog-attachment">
        <img v-if="isPreviewableImage(attachment) && previewSrc(attachment)" :src="previewSrc(attachment)" :alt="attachment.file_name || attachment.name" @click="preview(attachment)" />
        <span v-else @click="preview(attachment)"><Document /> {{ attachment.file_name || attachment.name }}</span>
        <button type="button" @click="removeAttachment(queueEditAttachments, attachment.id)">×</button>
      </div>
    </div>
    <template #footer>
      <el-button @click="queueEditVisible = false">取消</el-button>
      <el-button type="primary" @click="saveQueueEdit">保存</el-button>
    </template>
  </el-dialog>

</template>

<style scoped>
.status-strip {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 4px 0;
  overflow-x: auto;
  overflow-y: hidden;
}
.queue-strip {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: nowrap;
  gap: 6px;
}
.queue-item,
.processing-bar {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  box-sizing: border-box;
  width: max-content;
  min-width: 188px;
  max-width: 260px;
  min-height: 48px;
  border: 1px solid var(--soft-border);
  border-radius: 8px;
  background: var(--jb-panel);
  padding: 6px 8px;
  color: var(--jb-text);
  font-size: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);
}
.processing-bar {
  border-color: color-mix(in srgb, var(--jb-accent) 45%, var(--soft-border));
  background: color-mix(in srgb, var(--jb-accent) 12%, var(--jb-panel));
  color: var(--jb-text);
}
.queue-status-badge {
  flex: 0 0 auto;
  border: 1px solid var(--jb-accent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--jb-accent) 22%, var(--jb-panel));
  color: var(--jb-accent);
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 600;
}
.queue-status-badge.pending {
  border-color: transparent;
  background: var(--soft-fill);
  color: var(--jb-muted);
}
.queue-item.has-attachments,
.processing-bar.has-attachments {
  max-width: min(420px, 100%);
}
.queue-card-copy {
  display: grid;
  flex: 1 1 auto;
  gap: 1px;
  min-width: 0;
  line-height: 1.3;
}
.queue-card-copy strong,
.queue-card-copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.queue-card-copy strong { color: var(--jb-text); font-size: 12px; }
.queue-card-copy span { color: var(--jb-muted); font-size: 11px; }
.queue-item-attachments {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 0;
  min-width: 0;
  max-width: 22px;
  overflow: hidden;
}
.queue-item-attachments.carousel {
  flex: 0 0 auto;
  max-width: 46px;
}
.queue-attachment-nav {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 12px;
  height: 22px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #64748b;
  cursor: pointer;
}
.queue-attachment-nav :deep(svg) {
  width: 9px;
  height: 9px;
}
.queue-attachment {
  position: relative;
  width: 22px;
  height: 22px;
  overflow: hidden;
  border: 1px solid #dbe1ea;
  border-radius: 4px;
  background: var(--jb-panel);
  padding: 0;
  color: #64748b;
}
.queue-attachment-count {
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 0 2px;
  border-radius: 3px 0 0 0;
  background: rgba(15, 23, 42, 0.72);
  color: var(--jb-panel);
  font-size: 8px;
  line-height: 1.2;
}
.queue-attachment img { width: 100%; height: 100%; object-fit: cover; display: block; }
.queue-attachment :deep(svg) { width: 13px; height: 13px; }
.queue-item-actions,
.processing-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
  margin-left: auto;
}
.queue-item-actions .el-button,
.processing-append-button,
.processing-stop-button {
  width: 24px;
  height: 24px;
  min-width: 24px;
  padding: 0;
}
.queue-cancel-button {
  --el-button-text-color: #ef4444;
  --el-button-border-color: #fca5a5;
  --el-button-bg-color: var(--jb-panel)1f2;
  --el-button-hover-text-color: var(--jb-panel);
  --el-button-hover-border-color: #ef4444;
  --el-button-hover-bg-color: #ef4444;
}
.processing-append-button {
  --el-button-text-color: #2563eb;
  --el-button-border-color: color-mix(in srgb, var(--jb-accent) 45%, var(--soft-border));
  --el-button-bg-color: color-mix(in srgb, var(--jb-accent) 12%, var(--jb-panel));
  --el-button-hover-text-color: var(--jb-panel);
  --el-button-hover-border-color: #2563eb;
  --el-button-hover-bg-color: #2563eb;
}
.processing-stop-button {
  --el-button-text-color: #b45309;
  --el-button-border-color: #fbbf24;
  --el-button-bg-color: var(--jb-panel)beb;
  --el-button-hover-text-color: var(--jb-panel);
  --el-button-hover-border-color: #d97706;
  --el-button-hover-bg-color: #d97706;
}
.task-dialog-attachments { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.task-dialog-attachment {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  max-width: 220px;
  border: 1px solid color-mix(in srgb, var(--jb-accent) 22%, var(--jb-panel));
  border-radius: 6px;
  padding: 4px;
  color: #475569;
  font-size: 11px;
}
.task-dialog-attachment img { width: 72px; height: 54px; object-fit: cover; }
.task-dialog-attachment button {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 18px;
  height: 18px;
  border: 1px solid color-mix(in srgb, #ef4444 40%, var(--soft-border));
  border-radius: 50%;
  background: var(--jb-panel);
  color: #ef4444;
  cursor: pointer;
}
</style>
