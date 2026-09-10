<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { api, invoke } from '../../bridge/jcefBridge'
import { markdown } from '../../markdown.js'

const props = defineProps({ content: { type: String, default: '' } })
const html = computed(() => markdown.render(props.content))

function codeFileExtension(lang) {
  const language = String(lang || '').toLowerCase()
  const extensions = {
    javascript: 'js', js: 'js', typescript: 'ts', ts: 'ts', json: 'json', rust: 'rs', rs: 'rs',
    python: 'py', py: 'py', shell: 'sh', bash: 'sh', sh: 'sh', markdown: 'md', md: 'md', vue: 'vue',
    sql: 'sql', yaml: 'yaml', yml: 'yml', html: 'html', css: 'css', java: 'java', kt: 'kt', kotlin: 'kt',
  }
  return extensions[language] || 'txt'
}

function codeBlockSource(block) {
  const source = block.querySelector('.code-source')
  if (source) return source.value || source.textContent || ''
  return block.querySelector('code')?.textContent || ''
}


function openLinkExternally(event) {
  const anchor = event.target.closest('a')
  if (!anchor) return false
  const href = anchor.getAttribute('href') || ''
  if (!/^[a-z][a-z0-9+.-]*:/i.test(href)) return false
  event.preventDefault()
  invoke('link.open', { text: href })
  return true
}

async function click(event) {
  if (openLinkExternally(event)) return
  const action = event.target?.closest?.('[data-code-action]')
  if (!action) return
  const block = action.closest('.rendered-code-block')
  if (!block) return
  event.preventDefault()
  const source = codeBlockSource(block)
  if (!source && action.dataset.codeAction !== 'toggle-html') return
  if (action.dataset.codeAction === 'copy') {
    try {
      await invoke('code.copy', { text: source })
      ElMessage.success('复制成功')
    } catch (error) {
      ElMessage.error(`复制失败：${error?.message || String(error)}`)
    }
  } else if (action.dataset.codeAction === 'download') {
    try {
      const data = await api('code.save', {
        text: source,
        filename: `code.${codeFileExtension(block.dataset.codeLang)}`,
      })
      if (data?.saved) ElMessage.success(`已保存到 ${data.path}`)
    } catch (error) {
      ElMessage.error(`保存失败：${error?.message || String(error)}`)
    }
  } else if (action.dataset.codeAction === 'toggle-html') {
    const previewing = block.dataset.htmlView !== 'source'
    block.dataset.htmlView = previewing ? 'source' : 'preview'
    action.textContent = previewing ? '预览' : '源码'
  }
}
</script>

<template>
  <div class="markdown-body" v-html="html" @click="click" />
</template>
