<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { invoke } from '../../bridge/jcefBridge'

const props = defineProps({ content: String })
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[char]))
const renderer = new marked.Renderer()
renderer.code = ({ text, lang }) => {
  const language = String(lang || '').trim().split(/\s+/)[0] || 'text'
  return `<div class="code"><div class="code-head"><span>${escapeHtml(language)}</span><button type="button" data-copy>复制</button></div><pre><code class="language-${escapeHtml(language)}">${escapeHtml(text)}</code></pre></div>`
}
const html = computed(() => DOMPurify.sanitize(marked.parse(props.content || '', { renderer }), { ADD_ATTR: ['data-copy'] }))

async function click(event) {
  const button = event.target.closest('[data-copy]')
  if (!button) return
  const code = button.closest('.code')?.querySelector('code')?.textContent
  if (code == null) return
  button.disabled = true
  try {
    await invoke('code.copy', { text: code })
    ElMessage.success('复制成功')
  } catch (error) {
    ElMessage.error(`复制失败：${error?.message || String(error)}`)
  } finally {
    button.disabled = false
  }
}
</script>
<template><div class="markdown" v-html="html" @click="click" /></template>
