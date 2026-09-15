<script setup>
import { ElMessageBox } from 'element-plus'
import { ref, onMounted } from 'vue'
import { api } from '../bridge/jcefBridge'
import PageShell from '../components/common/PageShell.vue'

const rows = ref([])
const environments = ref([])
const createVisible = ref(false)
const sessionName = ref('')
const environmentId = ref(null)
const creating = ref(false)
const load = async () => {
  const [sessionRows, catalog] = await Promise.all([api('sessions.list'), api('environments.catalog')])
  rows.value = sessionRows
  environments.value = catalog.environments || []
  if (!environmentId.value) environmentId.value = environments.value[0]?.id || null
}

function openCreate() {
   sessionName.value = ''
  environmentId.value = environments.value[0]?.id || null
  createVisible.value = true
}

async function create() {
  if (creating.value) return
  if (!environmentId.value) throw new Error('请选择编码环境')
  creating.value = true
  try {
    await api('session.create', { title: sessionName.value.trim(), codingEnvironmentId: environmentId.value })
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
      <el-table-column prop="projectPath" label="关联项目" min-width="220">
        <template #default="{ row }">{{ row.projectPath }}</template>
      </el-table-column>
      <el-table-column prop="agentId" label="环境/Agent" width="120" />
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
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!sessionName.trim()" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </PageShell>
</template>
