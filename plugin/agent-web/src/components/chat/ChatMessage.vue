<script setup>
import MarkdownContent from './MarkdownContent.vue'
import { onBeforeUnmount, reactive, ref } from 'vue'
import { api, invoke } from '../../bridge/jcefBridge'
const props = defineProps({ item: Object })
const toolDetails = reactive({})
const toolLoading = reactive({})
const expandedTools = new Set()
const previewSrc = ref('')
const previewVisible = ref(false)
const runExpanded = ref(true)
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
    if (expandedTools.has(tool.id)) toolDetails[tool.id] = detail
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
      <button v-if="item.messageType==='agent_run'" class="agent-run-head" type="button" @click="runExpanded=!runExpanded">
        <span>
          <b><el-tag size="small" effect="plain">Agent 运行</el-tag> {{item.agentRun?.agentName}}</b>
          <small>{{item.agentRun?.receiver==='ai'?'作为模型回复投递':'作为用户消息加入队列'}}</small>
        </span>
        <em>{{item.agentRun?.status==='running'?'运行中':item.agentRun?.status==='completed'?'已完成':item.agentRun?.status==='failed'?'失败':'已取消'}} {{runExpanded?'⌃':'⌄'}}</em>
      </button>
      <div v-show="item.messageType!=='agent_run'||runExpanded" class="message-body">
        <div v-if="item.messageType==='agent_run'&&item.agentRun?.prompt" class="agent-run-prompt"><b>运行输入</b><span>{{item.agentRun.prompt}}</span></div>
        <span v-if="item.generating" class="running">生成中…</span>
        <div v-if="item.role==='user'" class="plain">{{item.content}}</div>
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
