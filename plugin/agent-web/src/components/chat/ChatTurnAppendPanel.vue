<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { attachmentKind, attachmentUrl, formatAttachmentSize, isImageAttachment } from './attachmentUtils.js'

const props = defineProps({
  sessionId: { type: Number, required: true },
  appends: { type: Array, default: () => [] },
  labels: { type: Object, default: () => ({}) },
  formatCreatedAt: { type: Function, required: true }
})
const emit = defineEmits(['preview'])

const expanded = ref(true)
const expandedAppendIds = ref(new Set())
const appendList = ref(null)
const visibleAppends = computed(() => props.appends.filter((item) => item.status !== 'cancelled'))

watch(
  () => visibleAppends.value.length,
  async () => {
    await nextTick()
    if (appendList.value) appendList.value.scrollTop = appendList.value.scrollHeight
  }
)

function label(key, fallback) {
  return props.labels?.[key] || fallback
}

function summary(item) {
  const text = item.content?.trim() || label('attachmentOnly', '附件消息')
  const chars = Array.from(text)
  return chars.length > 42 ? `${chars.slice(0, 42).join('')}...` : text
}

function statusLabel(status) {
  return label(`status_${status}`, status)
}

function isAppendExpanded(item) {
  return expandedAppendIds.value.has(item.append_id)
}

function toggleAppend(item) {
  const nextExpandedAppendIds = new Set(expandedAppendIds.value)
  if (nextExpandedAppendIds.has(item.append_id)) nextExpandedAppendIds.delete(item.append_id)
  else nextExpandedAppendIds.add(item.append_id)
  expandedAppendIds.value = nextExpandedAppendIds
}

function previewAttachment(attachment) {
  const url = attachmentUrl(attachment, props.sessionId)
  if (!url) return
  if (isImageAttachment(attachment)) {
    emit('preview', { ...attachment, url })
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}
</script>

<template>
  <section v-if="visibleAppends.length" class="turn-append-section">
    <button type="button" class="turn-append-header" @click="expanded = !expanded">
      <span class="turn-append-header-copy">
        <span class="turn-append-branch">↳</span>
        <strong>{{ label('title', '追加消息') }}</strong>
        <em>{{ visibleAppends.length }} {{ label('countUnit', '条') }}</em>
      </span>
      <span class="turn-append-caret">{{ expanded ? '⌃' : '⌄' }}</span>
    </button>

    <div v-if="expanded" ref="appendList" class="turn-append-list">
      <div v-for="(item, index) in visibleAppends" :key="item.append_id" class="turn-append-entry">
        <button type="button" class="turn-append-summary" @click="toggleAppend(item)">
          <span class="turn-append-index">{{ index + 1 }}</span>
          <span class="turn-append-copy">
            <strong>{{ summary(item) }}</strong>
            <small v-if="item.attachment_items?.length">
              {{ label('attachmentCount', '附件') }} {{ item.attachment_items.length }}
            </small>
          </span>
          <time>{{ formatCreatedAt(item.created_at) }}</time>
          <em :class="`status-${item.status}`">{{ statusLabel(item.status) }}</em>
          <span class="turn-append-row-caret">{{ isAppendExpanded(item) ? '⌃' : '⌄' }}</span>
        </button>
        <div v-if="isAppendExpanded(item)" class="turn-append-detail">
          <div class="turn-append-detail-content">
            <div v-if="item.content" class="turn-append-content">{{ item.content }}</div>
            <div v-if="item.attachment_items?.length" class="turn-append-attachments">
              <component
                  :is="isImageAttachment(attachment) ? 'button' : 'a'"
                  v-for="attachment in item.attachment_items"
                  :key="attachment.id"
                  :type="isImageAttachment(attachment) ? 'button' : undefined"
                  class="turn-append-attachment-card clickable"
                  :href="isImageAttachment(attachment) ? undefined : attachmentUrl(attachment, sessionId)"
                  :target="isImageAttachment(attachment) ? undefined : '_blank'"
                  :rel="isImageAttachment(attachment) ? undefined : 'noopener noreferrer'"
                  :title="attachment.file_name"
                  @click="isImageAttachment(attachment) ? previewAttachment(attachment) : undefined"
              >
              <span class="turn-append-attachment-preview">
                <img
                    v-if="isImageAttachment(attachment)"
                    :src="attachmentUrl(attachment, sessionId)"
                    :alt="attachment.file_name"
                    loading="lazy"
                />
                <el-icon v-else><Document/></el-icon>
              </span>
                <span class="turn-append-attachment-meta">
                <strong :title="attachment.file_name">{{ attachment.file_name }}</strong>
                <small>{{
                    attachmentKind(attachment, label('file', '文件'))
                  }} · {{ formatAttachmentSize(attachment.size) }}</small>
              </span>
              </component>
            </div>
            <time class="turn-append-detail-time">{{ formatCreatedAt(item.created_at) }}</time>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.turn-append-section { margin-top: 10px; overflow: hidden; border: 1px solid var(--el-border-color-light); border-radius: 7px; background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.turn-append-header { display: flex; width: 100%; align-items: center; justify-content: space-between; border: 0; background: var(--el-fill-color); padding: 7px 10px; color: inherit; cursor: pointer; }
.turn-append-header-copy { display: inline-flex; align-items: center; gap: 7px; }
.turn-append-branch { color: var(--el-color-primary); font-size: 15px; font-weight: 700; }
.turn-append-header strong { font-size: 12px; }
.turn-append-header em { color: var(--el-color-primary); font-size: 11px; font-style: normal; }
.turn-append-caret { color: var(--el-text-color-secondary); font-size: 11px; }
.turn-append-list { display: grid; max-height: 342px; gap: 5px; padding: 7px; overflow-y: auto; }
.turn-append-entry { overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 5px; background: var(--el-bg-color); }
.turn-append-summary { display: grid; width: 100%; grid-template-columns: 24px minmax(0, 1fr) auto auto 14px; gap: 7px; align-items: center; border: 0; background: transparent; padding: 7px 8px; color: inherit; cursor: pointer; text-align: left; }
.turn-append-index { display: grid; width: 20px; height: 20px; place-items: center; border-radius: 4px; background: var(--el-fill-color-light); color: var(--el-color-primary); font-size: 10px; }
.turn-append-copy { display: flex; min-width: 0; align-items: center; gap: 6px; }
.turn-append-copy strong { overflow: hidden; font-size: 11px; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }
.turn-append-copy small, .turn-append-summary time { color: var(--el-text-color-secondary); font-size: 10px; white-space: nowrap; }
.turn-append-summary em { border-radius: 999px; background: var(--el-fill-color); padding: 2px 6px; color: var(--el-text-color-secondary); font-size: 10px; font-style: normal; white-space: nowrap; }
.turn-append-summary em.status-delivered { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.turn-append-summary em.status-claimed { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.turn-append-summary em.status-failed { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.turn-append-row-caret { color: var(--el-text-color-placeholder); font-size: 10px; }
.turn-append-detail { max-height: 360px; overflow-y: auto; border-top: 1px solid var(--el-border-color-lighter); padding: 9px 10px 10px 25px; }
.turn-append-detail-content { overflow: hidden; border: 1px solid var(--el-border-color-light); border-left: 3px solid var(--el-color-primary); border-radius: 6px; background: var(--el-fill-color-lighter); padding: 9px 10px 8px; }
.turn-append-content { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--el-text-color-primary); font-size: 12px; line-height: 1.55; }
.turn-append-attachments { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.turn-append-attachment-card { display: grid; width: 188px; overflow: hidden; border: 1px solid var(--el-border-color); border-radius: 7px; background: var(--el-bg-color); padding: 0; color: inherit; cursor: default; text-align: left; }
.turn-append-attachment-card.clickable { cursor: pointer; }
.turn-append-attachment-preview { display: grid; width: 100%; height: 130px; place-items: center; overflow: hidden; background: var(--el-fill-color-dark); color: var(--el-text-color-secondary); }
.turn-append-attachment-preview img { display: block; width: 100%; height: 100%; object-fit: cover; }
.turn-append-attachment-preview :deep(.el-icon) { width: 34px; height: 34px; font-size: 34px; }
.turn-append-attachment-meta { display: grid; gap: 3px; min-width: 0; padding: 8px 9px 9px; }
.turn-append-attachment-meta strong { overflow: hidden; font-size: 11px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.turn-append-attachment-meta small { color: var(--el-text-color-secondary); font-size: 10px; text-transform: lowercase; }
.turn-append-detail-time { display: block; margin-top: 8px; color: var(--el-text-color-secondary); font-size: 10px; }
</style>
