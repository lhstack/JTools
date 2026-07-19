<script setup>
import { ElMessageBox } from 'element-plus'
import { ref, onMounted } from 'vue'
import { api } from '../bridge/jcefBridge'
import PageShell from '../components/common/PageShell.vue'

const rows = ref([])
const createVisible = ref(false)
const sessionType = ref('project')
const sessionName = ref('')
const creating = ref(false)
const load = () => api('sessions.list').then(value => rows.value = value)

function openCreate() {
  sessionType.value = 'project'
  sessionName.value = ''
  createVisible.value = true
}

async function create() {
  if (creating.value) return
  creating.value = true
  try {
    await api('session.create', { title: sessionName.value.trim(), sessionType: sessionType.value })
    createVisible.value = false
    await load()
  } finally {
    creating.value = false
  }
}

async function rename(item) {
  const { value } = await ElMessageBox.prompt('输入新的会话名称', '重命名', { inputValue: item.title })
  await api('session.rename', { id: item.id, title: value })
  load()
}

async function remove(item) {
  await ElMessageBox.confirm(`确定删除会话 ${item.title}？`, '删除', { type: 'warning' })
  await api('session.delete', { id: item.id })
  load()
}

onMounted(load)
</script>

<template>
  <PageShell title="会话管理">
    <template #actions><el-button type="primary" @click="openCreate">新建会话</el-button></template>
    <el-table :data="rows" height="100%">
      <el-table-column prop="title" label="名称" />
      <el-table-column prop="sessionType" label="类型" width="100">
        <template #default="{ row }">{{ row.sessionType === 'global' ? '全局' : '项目' }}</template>
      </el-table-column>
      <el-table-column prop="projectPath" label="关联项目" min-width="220">
        <template #default="{ row }">{{ row.sessionType === 'global' ? '所有项目' : row.projectPath }}</template>
      </el-table-column>
      <el-table-column prop="agentId" label="Agent" width="90" />
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column width="160">
        <template #default="{ row }">
          <el-button link @click="rename(row)">重命名</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建会话" width="420px" append-to-body>
      <el-form-item label="会话名称" required><el-input v-model="sessionName" maxlength="80" show-word-limit /></el-form-item>
      <el-form-item label="会话范围" required>
      <el-radio-group v-model="sessionType" class="session-type-options">
        <el-radio value="project">
          <div><strong>项目会话</strong><small>仅在当前项目中可见</small></div>
        </el-radio>
        <el-radio value="global">
          <div><strong>全局会话</strong><small>所有项目共享，可自由切换</small></div>
        </el-radio>
      </el-radio-group>
      </el-form-item>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!sessionName.trim()" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </PageShell>
</template>

<style scoped>
.session-type-options { display: grid; gap: 12px; width: 100%; }
.session-type-options :deep(.el-radio) { height: auto; margin: 0; padding: 14px; border: 1px solid var(--jb-border); border-radius: 10px; }
.session-type-options :deep(.el-radio.is-checked) { border-color: var(--jb-accent); background: color-mix(in srgb, var(--jb-accent) 10%, transparent); }
.session-type-options div { display: grid; gap: 4px; }
.session-type-options small { color: var(--jb-muted); }
</style>
