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

// 链接改为交给 IDE 用外部浏览器打开，避免在 JCEF 内导航导致整个对话页跳走无法返回。
function openLinkExternally(event) {
  const anchor = event.target.closest('a')
  if (!anchor) return false
  const href = anchor.getAttribute('href') || ''
  // 只接管带协议的外链（http/https/mailto 等），锚点与空链接交回默认行为。
  if (!/^[a-z][a-z0-9+.-]*:/i.test(href)) return false
  event.preventDefault()
  invoke('link.open', { text: href })
  return true
}

async function click(event) {
  if (openLinkExternally(event)) return
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
