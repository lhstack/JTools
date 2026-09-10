<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../bridge/jcefBridge'
import PageShell from '../components/common/PageShell.vue'

const fields = ref([])
const definitions = [
  { title: '对话输入', desc: '输入框润色和上下文压缩的全局回退', keys: ['chat.polish.agent_id', 'coding.compaction_agent_id'] },
  { title: '模型执行', desc: '模型调用循环、重试和历史占用估算', keys: ['model.max_tool_call_rounds', 'model.max_retries', 'model.retry_interval_ms', 'message.history_token_ratio'] },
  { title: '模型网络', desc: '模型 HTTP 连接、DNS 与请求超时', prefixes: ['model.dns_', 'model.http_'] },
  { title: '网页获取', desc: 'web_fetch 的代理、超时和响应大小', prefixes: ['web_fetch.'] },
  { title: '命令工具', desc: 'bash 工具捕获输出上限', prefixes: ['bash.', 'coding.bash.'] },
]

const groups = computed(() => definitions.map((group) => ({
  title: group.title,
  desc: group.desc,
  items: fields.value.filter((item) => belongs(item.key, group)),
})).filter((group) => group.items.length))

function belongs(key, group) {
  if (group.keys?.includes(key)) return true
  return group.prefixes?.some((prefix) => key.startsWith(prefix)) || false
}

async function save() {
  const values = {}
  fields.value.forEach((item) => { values[item.key] = String(item.value) })
  await api('config.save', { values })
  ElMessage.success('全局设置已保存')
}

onMounted(async () => {
  fields.value = (await api('config.get')).map((item) => ({
    ...item,
    value: item.type === 'boolean' ? String(item.value) === 'true' : item.type === 'number' ? Number(item.value) : item.value,
  }))
})
</script>

<template>
  <PageShell title="全局设置">
    <template #actions><el-button type="primary" @click="save">保存设置</el-button></template>
    <div class="settings-page">
      <section v-for="group in groups" :key="group.title" class="settings-group">
        <header>
          <strong>{{ group.title }}</strong>
          <p>{{ group.desc }}</p>
        </header>
        <div v-for="item in group.items" :key="item.key" class="setting-row">
          <div>
            <label>{{ item.label }}</label>
            <small>{{ item.description || item.key }}</small>
          </div>
          <el-switch v-if="item.type === 'boolean'" v-model="item.value" />
          <el-input-number v-else-if="item.type === 'number'" v-model="item.value" :step="item.key === 'message.history_token_ratio' ? 0.1 : 1" />
          <el-select v-else-if="item.type === 'select'" v-model="item.value" class="setting-select">
            <el-option v-for="option in item.options || []" :key="option.value" :value="option.value" :label="option.label" />
          </el-select>
          <el-input v-else v-model="item.value" />
        </div>
      </section>
    </div>
  </PageShell>
</template>
