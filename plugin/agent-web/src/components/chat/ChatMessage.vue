<script setup>
import MarkdownContent from './MarkdownContent.vue'
import { invoke } from '../../bridge/jcefBridge'
defineProps({ item: Object })
function toolState(tool) {
  if (!tool.finished) return { icon: '○', label: '进行中', css: 'running' }
  if (tool.failed) return { icon: '✕', label: '失败', css: 'failed' }
  return { icon: '✓', label: '完成', css: 'completed' }
}
</script>
<template>
  <article class="message" :class="item.role">
    <div class="avatar">{{item.role==='user'?'我':'AI'}}</div>
    <div class="bubble">
      <span v-if="item.generating" class="running">生成中…</span>
      <div v-if="item.role==='user'" class="plain">{{item.content}}</div>
      <div v-if="item.attachments?.length" class="message-attachments">
        <article v-for="x in item.attachments" :key="x.id" class="message-attachment"><img v-if="x.kind==='image'&&x.previewUrl" :src="x.previewUrl" :alt="x.name"/><div v-else class="file-preview">附件</div><footer><strong>{{x.name}}</strong><small>{{x.mimeType||x.kind}} · {{Math.max(1,Math.ceil(x.size/1024))}} KB</small></footer></article>
      </div>
      <MarkdownContent v-else-if="item.role!=='user'" :content="item.content"/>
      <el-collapse v-if="item.reasoning||item.tools?.length">
        <el-collapse-item v-if="item.reasoning" title="推理"><pre class="reasoning">{{item.reasoning}}</pre></el-collapse-item>
        <el-collapse-item v-if="item.tools?.length" :title="`工具调用 (${item.tools.filter(x=>x.finished).length}/${item.tools.length})`">
          <el-collapse v-for="tool in item.tools" :key="tool.id" class="tool-call-list">
            <el-collapse-item>
              <template #title><span class="tool-state" :class="toolState(tool).css"><b>{{toolState(tool).icon}}</b><span>{{tool.name}}</span><small>{{toolState(tool).label}}</small></span></template>
              <b>入参</b><pre>{{tool.args}}</pre><b>出参</b><pre>{{tool.result||'调用中'}}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-collapse-item>
      </el-collapse>
      <footer><span>{{item.createdAt}} <el-popover v-if="item.usage" placement="top" trigger="hover" :width="230"><template #reference><span class="message-usage">{{item.usage}}</span></template><div class="usage-detail"><strong>Token usage</strong><div v-for="detail in item.usageDetails" :key="detail.label"><span>{{detail.label}}</span><b>{{detail.value.toLocaleString()}}</b></div></div></el-popover></span><el-button v-if="item.deletable" link type="danger" @click="invoke('message.delete',{id:item.id})">删除</el-button></footer>
    </div>
  </article>
</template>
