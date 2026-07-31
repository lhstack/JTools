<script setup>
import MarkdownContent from './MarkdownContent.vue'
import { onBeforeUnmount, reactive, ref } from 'vue'
import { api, invoke } from '../../bridge/jcefBridge'
const props = defineProps({ item: Object })
const toolDetails = reactive({})
const toolLoading = reactive({})
const expandedTools = new Set()
const MAX_TOOL_DETAIL_CHARS = 16_384
function limitToolDetail(value) {
  const text = String(value ?? '')
  if (text.length <= MAX_TOOL_DETAIL_CHARS) return text
  return text.slice(0, MAX_TOOL_DETAIL_CHARS) + `\n\n[内容已截断，原始内容还剩 ${text.length - MAX_TOOL_DETAIL_CHARS} 个字符；请使用专门工具按范围读取。]`
}
function limitToolDetails(detail) {
  return { args: limitToolDetail(detail?.args), result: limitToolDetail(detail?.result) }
}
const previewSrc = ref('')
const previewVisible = ref(false)
const runExpanded = ref(true)
const appendExpanded = ref(false)
const expandedAppendMessages = ref(new Set())
function appendMessageExpanded(messageId) {
  return expandedAppendMessages.value.has(messageId)
}
function toggleAppendMessage(messageId) {
  const next = new Set(expandedAppendMessages.value)
  if (next.has(messageId)) next.delete(messageId)
  else next.add(messageId)
  expandedAppendMessages.value = next
}
function openAttachment(att) {
  if (att.kind === 'image' && att.previewUrl) {
    previewSrc.value = att.previewUrl
    previewVisible.value = true
  } else {
    invoke('attachment.open', { text: att.path })
  }
}
function toolState(tool) {
  if (!tool.finished) return { icon: '○', label: '进行中', css: 'running' }
  if (tool.failed) return { icon: '✕', label: '失败', css: 'failed' }
  return { icon: '✓', label: '完成', css: 'completed' }
}
async function toolChanged(tool, expanded) {
  if (!expanded) {
    expandedTools.delete(tool.id)
    delete toolDetails[tool.id]
    delete toolLoading[tool.id]
    return
  }
  expandedTools.add(tool.id)
  toolLoading[tool.id] = true
  try {
    const detail = await api('tool.detail', { messageId: props.item.id, callId: tool.id })
    if (expandedTools.has(tool.id)) toolDetails[tool.id] = limitToolDetails(detail)
  } catch (error) {
    if (expandedTools.has(tool.id)) {
      toolDetails[tool.id] = { args: '', result: `加载失败：${error?.message || String(error)}` }
    }
  } finally {
    if (expandedTools.has(tool.id)) toolLoading[tool.id] = false
    else delete toolLoading[tool.id]
  }
}
onBeforeUnmount(() => {
  expandedTools.clear()
  Object.keys(toolDetails).forEach(id => delete toolDetails[id])
  Object.keys(toolLoading).forEach(id => delete toolLoading[id])
})
</script>
<template>
  <article class="message" :class="item.role">
    <div class="avatar">{{item.role==='user'?'我':item.messageType==='agent_run'?'A':'AI'}}</div>
    <div class="bubble">
      <div v-if="item.actorLabel" class="message-actor">{{item.actorLabel}}</div>
      <button v-if="item.messageType==='agent_run'" class="agent-run-head" type="button" @click="runExpanded=!runExpanded">
        <span>
          <b><el-tag size="small" effect="plain">Agent 运行</el-tag> {{item.agentRun?.agentName}}</b>
          <small>{{item.agentRun?.receiver==='user'?'作为模型回复投递':'作为用户消息加入队列'}}</small>
        </span>
        <em>{{item.agentRun?.status==='running'?'运行中':item.agentRun?.status==='completed'?'已完成':item.agentRun?.status==='failed'?'失败':'已取消'}} {{runExpanded?'⌃':'⌄'}}</em>
      </button>
      <div v-show="item.messageType!=='agent_run'||runExpanded" class="message-body">
        <div v-if="item.messageType==='agent_run'&&item.agentRun?.prompt" class="agent-run-prompt"><b>运行输入</b><span>{{item.agentRun.prompt}}</span></div>
        <span v-if="item.generating" class="running">生成中…</span>
        <div v-if="item.role==='user'" class="plain">{{item.content}}</div>
        <section v-if="item.role==='user'&&item.appendMessages?.length" class="append-messages">
          <button
            class="append-message-head"
            type="button"
            :aria-expanded="appendExpanded"
            @click="appendExpanded=!appendExpanded"
          >
            <span class="append-message-icon" aria-hidden="true">↳</span>
            <strong>追加消息</strong>
            <small>{{item.appendMessages.length}} 条</small>
            <em>{{appendExpanded?'⌃':'⌄'}}</em>
          </button>
          <div v-show="appendExpanded" class="append-message-list">
            <article
              v-for="(message,index) in item.appendMessages"
              :key="message.id"
              class="append-message-item"
              :class="{ expanded: appendMessageExpanded(message.id) }"
            >
              <button
                class="append-message-item-head"
                type="button"
                :aria-expanded="appendMessageExpanded(message.id)"
                @click="toggleAppendMessage(message.id)"
              >
                <span class="append-message-order"><b>{{index+1}}</b>第 {{index+1}} 条追加</span>
                <span class="append-message-preview">{{message.content}}</span>
                <time v-if="message.createdAt">{{message.createdAt}}</time>
                <em>{{appendMessageExpanded(message.id)?'⌃':'⌄'}}</em>
              </button>
              <div v-show="appendMessageExpanded(message.id)" class="append-message-content">{{message.content}}</div>
            </article>
          </div>
        </section>
        <div v-if="item.attachments?.length" class="message-attachments">
          <article v-for="x in item.attachments" :key="x.id" class="message-attachment" @click="openAttachment(x)"><img v-if="x.kind==='image'&&x.previewUrl" :src="x.previewUrl" :alt="x.name"/><div v-else class="file-preview">附件</div><footer><strong>{{x.name}}</strong><small>{{x.mimeType||x.kind}} · {{Math.max(1,Math.ceil(x.size/1024))}} KB</small></footer></article>
        </div>
        <MarkdownContent v-else-if="item.role!=='user'" :content="item.content"/>
        <div v-if="item.messageType==='agent_run'&&item.agentRun?.error" class="agent-run-error">{{item.agentRun.error}}</div>
        <el-collapse v-if="item.reasoning||item.tools?.length">
          <el-collapse-item v-if="item.reasoning" title="推理"><pre class="reasoning">{{item.reasoning}}</pre></el-collapse-item>
          <el-collapse-item v-if="item.tools?.length" :title="`工具调用 (${item.tools.filter(x=>x.finished).length}/${item.tools.length})`">
            <div class="tool-call-scroll">
              <el-collapse v-for="tool in item.tools" :key="tool.id" class="tool-call-list" @change="active => toolChanged(tool, active.includes(tool.id))">
                <el-collapse-item :name="tool.id">
                  <template #title><span class="tool-state" :class="toolState(tool).css"><b>{{toolState(tool).icon}}</b><span>{{tool.name}}</span><small>{{toolState(tool).label}}</small></span></template>
                  <div v-if="toolLoading[tool.id]" class="running">加载工具详情...</div>
                  <template v-else-if="toolDetails[tool.id]">
                    <b>入参</b><pre>{{toolDetails[tool.id].args}}</pre><b>出参</b><pre>{{toolDetails[tool.id].result||'调用中'}}</pre>
                  </template>
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-collapse-item>
        </el-collapse>
        <footer><span>{{item.createdAt}} <el-popover v-if="item.usage" placement="top" trigger="hover" :width="230"><template #reference><span class="message-usage">{{item.usage}}</span></template><div class="usage-detail"><strong>Token usage</strong><div v-for="detail in item.usageDetails" :key="detail.label"><span>{{detail.label}}</span><b>{{detail.value.toLocaleString()}}</b></div></div></el-popover></span><el-button v-if="item.deletable" link type="danger" @click="invoke('message.delete',{id:item.id})">删除</el-button></footer>
        <el-image-viewer v-if="previewVisible" :url-list="[previewSrc]" @close="previewVisible=false"/>
      </div>
    </div>
  </article>
</template>
