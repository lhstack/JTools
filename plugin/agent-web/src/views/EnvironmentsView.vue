<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../bridge/jcefBridge'
import PageShell from '../components/common/PageShell.vue'
import ListEditor from '../components/common/ListEditor.vue'

const environments = ref([])
const agents = ref([])
const skills = ref([])
const tools = ref([])
const pluginFunctionGroups = ref([])
const selectedId = ref(null)
const saving = ref(false)
const sections = ref(['basic'])
const toolQuery = ref('')
const pluginQuery = ref('')
const skillQuery = ref('')
const selected = computed(() => environments.value.find((item) => item.id === selectedId.value) || null)
const config = reactive(emptyConfig())

const contextMeta = {
  user_profile: { label: '用户画像', description: '稳定的技术背景与能力' },
  coding_style: { label: '编码风格', description: '命名、组织、错误处理与代码风格' },
  workflow: { label: '工作流习惯', description: '分析、修改、验证与提交的习惯' },
  interaction: { label: '交互偏好', description: '讨论方式、语言与输出细节' },
}

const viewRows = [
  { key: 'image', label: '图片', tool: 'view_image' },
  { key: 'audio', label: '音频', tool: 'view_audio' },
  { key: 'video', label: '视频', tool: 'view_video' },
  { key: 'file', label: '文件', tool: 'view_files' },
]

const toolRows = computed(() => (tools.value.length ? tools.value : Object.keys(config.tools)).map((name) => ({
  name,
  description: toolDescription(name),
})))
const filteredTools = computed(() => {
  const query = toolQuery.value.trim().toLowerCase()
  return toolRows.value.filter((row) => !query || `${row.name} ${row.description}`.toLowerCase().includes(query))
})
const visiblePluginGroups = computed(() => {
  const query = pluginQuery.value.trim().toLowerCase()
  return pluginFunctionGroups.value
    .map((group) => ({
      ...group,
      functions: group.functions.filter((item) => !query || `${item.name} ${item.description}`.toLowerCase().includes(query)),
    }))
    .filter((group) => group.functions.length)
})
const filteredSkills = computed(() => {
  const query = skillQuery.value.trim().toLowerCase()
  return skills.value.filter((skill) => !query || `${skill.name} ${skill.description || ''}`.toLowerCase().includes(query))
})
const selectedPluginKeys = computed(() => pluginFunctionGroups.value.flatMap((group) => group.functions.map((item) => item.key)).filter((key) => config.plugin_functions[key] ?? config.plugin_functions_include_new))

function toolDescription(name) {
  const map = {
    read_project_files: '读取项目文件',
    write_project_files: '创建或覆盖项目文件',
    replace_project_text: '精确替换项目文本',
    find_project_files: '按路径查找文件',
    find_project_classes: '查找类或等价类型',
    search_project_text: '搜索项目文本',
    format_project_files: 'IDE 格式化',
    inspect_project_files: 'IDE Inspection',
    build_project: '构建项目',
    bash: '执行命令',
    web_fetch: 'HTTP 请求',
    cli: 'CLI / MCP 调用',
    get_time: '获取当前时间',
    skills_list: '列出技能',
    skills_view: '读取技能',
  }
  return map[name] || name
}

function emptyConfig() {
  return {
    system_prompt: '',
    contexts: {
      user_profile: { content: '', max_chars: 2000 },
      coding_style: { content: '', max_chars: 4000 },
      workflow: { content: '', max_chars: 4000 },
      interaction: { content: '', max_chars: 2000 },
    },
    tools: {},
    resources: { mcp: {}, mcp_include_new: true, skills: {}, skills_include_new: true },
    plugin_functions: {},
    plugin_functions_include_new: false,
    skill_prompt_enabled: false,
    compaction: { agent_id: null },
    view_resources: {
      image: { enabled: false, agent_id: null },
      audio: { enabled: false, agent_id: null },
      video: { enabled: false, agent_id: null },
      file: { enabled: false, agent_id: null },
    },
  }
}

function hydrateConfig(value) {
  const defaults = emptyConfig()
  const source = value || {}
  Object.assign(config, {
    ...defaults,
    ...source,
    contexts: Object.fromEntries(Object.keys(defaults.contexts).map((key) => [
      key,
      { ...defaults.contexts[key], ...(source.contexts?.[key] || {}) },
    ])),
    tools: { ...Object.fromEntries((tools.value || []).map((name) => [name, true])), ...(source.tools || {}) },
    resources: { ...defaults.resources, ...(source.resources || {}) },
    plugin_functions: { ...(source.plugin_functions || {}) },
    plugin_functions_include_new: source.plugin_functions_include_new === true,
    compaction: { ...defaults.compaction, ...(source.compaction || {}) },
    view_resources: {
      image: { ...defaults.view_resources.image, ...(source.view_resources?.image || {}) },
      audio: { ...defaults.view_resources.audio, ...(source.view_resources?.audio || {}) },
      video: { ...defaults.view_resources.video, ...(source.view_resources?.video || {}) },
      file: { ...defaults.view_resources.file, ...(source.view_resources?.file || {}) },
    },
  })
}

function selectedSkillIds() {
  return skills.value
    .filter((skill) => config.resources.skills[skill.name] ?? config.resources.skills_include_new)
    .map((skill) => skill.name)
}

function setSelectedSkillIds(ids) {
  const selectedIds = new Set(ids)
  config.resources.skills = Object.fromEntries(skills.value.map((skill) => [skill.name, selectedIds.has(skill.name)]))
}

function setSelectedPluginKeys(ids) {
  const selectedIds = new Set(ids)
  const known = pluginFunctionGroups.value.flatMap((group) => group.functions.map((item) => item.key))
  config.plugin_functions = Object.fromEntries(known.map((key) => [key, selectedIds.has(key)]))
}

function pluginGroupCount(group) {
  return group.functions.filter((item) => config.plugin_functions[item.key] ?? config.plugin_functions_include_new).length
}

function selectPluginGroup(group) {
  const next = new Set(selectedPluginKeys.value)
  group.functions.forEach((item) => next.add(item.key))
  setSelectedPluginKeys([...next])
}

function clearPluginGroup(group) {
  const keys = new Set(group.functions.map((item) => item.key))
  setSelectedPluginKeys(selectedPluginKeys.value.filter((key) => !keys.has(key)))
}

function selectVisiblePlugins() {
  const next = new Set(selectedPluginKeys.value)
  visiblePluginGroups.value.flatMap((group) => group.functions).forEach((item) => next.add(item.key))
  setSelectedPluginKeys([...next])
}

function clearVisiblePlugins() {
  const keys = new Set(visiblePluginGroups.value.flatMap((group) => group.functions.map((item) => item.key)))
  setSelectedPluginKeys(selectedPluginKeys.value.filter((key) => !keys.has(key)))
}

function setToolEnabled(name, enabled) {
  config.tools[name] = enabled
}

async function load(selectId = selectedId.value) {
  const data = await api('environments.catalog')
  environments.value = data.environments || []
  agents.value = data.agents || []
  skills.value = data.skills || []
  tools.value = data.tools || []
  pluginFunctionGroups.value = data.pluginFunctionGroups || []
  const target = environments.value.find((item) => item.id === selectId) || environments.value[0] || null
  selectedId.value = target?.id || null
  if (target) hydrateConfig(target.config)
  else hydrateConfig(emptyConfig())
}

function selectEnvironment(item) {
  selectedId.value = item.id
  hydrateConfig(item.config)
}

async function createEnvironment() {
  const { value } = await ElMessageBox.prompt('输入编码环境名称', '新建编码环境', { inputValue: '默认环境' })
  const created = await api('environment.create', { name: value })
  await load(created.id)
  ElMessage.success('编码环境已创建')
}

async function renameEnvironment() {
  if (!selected.value) return
  const { value } = await ElMessageBox.prompt('输入新的环境名称', '重命名', { inputValue: selected.value.name })
  await api('environment.rename', { id: selected.value.id, name: value })
  await load(selected.value.id)
}

async function removeEnvironment() {
  if (!selected.value) return
  await ElMessageBox.confirm(`确定删除编码环境 ${selected.value.name}？`, '删除', { type: 'warning' })
  await api('environment.delete', { id: selected.value.id })
  await load()
}

async function save() {
  if (!selected.value) return
  saving.value = true
  try {
    const saved = await api('environment.save', { id: selected.value.id, config: JSON.parse(JSON.stringify(config)) })
    const index = environments.value.findIndex((item) => item.id === saved.id)
    if (index >= 0) environments.value[index] = saved
    ElMessage.success('编码环境已保存')
  } catch (error) {
    ElMessage.error(error.message || String(error))
  } finally {
    saving.value = false
  }
}

onMounted(() => load().catch((error) => ElMessage.error(error.message || String(error))))
</script>

<template>
  <PageShell title="编码环境" :loading="saving">
    <div class="management-split agent-layout">
      <ListEditor :items="environments" :selected="selectedId" @select="selectEnvironment" @create="createEnvironment" />
      <el-scrollbar v-if="selected" class="agent-scroll">
        <div class="agent-summary">
          <div>
            <h3>{{ selected.name }}</h3>
            <p>只作用于编码会话。工具、插件函数、技能和压缩 Agent 按环境绑定。</p>
          </div>
          <div>
            <el-button @click="renameEnvironment">重命名</el-button>
            <el-button type="danger" plain @click="removeEnvironment">删除</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存环境</el-button>
          </div>
        </div>
        <el-collapse v-model="sections" class="config-collapse agent-collapse">
          <el-collapse-item name="basic">
            <template #title><span class="collapse-title">基础</span></template>
            <el-form label-position="top" class="section-form">
              <el-form-item label="系统提示词">
                <el-input v-model="config.system_prompt" type="textarea" :rows="8" resize="vertical" />
              </el-form-item>
              <el-form-item label="压缩 Agent">
                <el-select v-model="config.compaction.agent_id" clearable placeholder="继承全局设置">
                  <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="contexts">
            <template #title><span class="collapse-title">上下文</span></template>
            <div class="persona-grid">
              <section v-for="(meta, key) in contextMeta" :key="key" class="persona-card">
                <header>
                  <div>
                    <strong>{{ meta.label }}</strong>
                    <small>{{ meta.description }}</small>
                  </div>
                  <el-input-number v-model="config.contexts[key].max_chars" :min="200" :step="200" controls-position="right" />
                </header>
                <el-input v-model="config.contexts[key].content" type="textarea" :rows="6" resize="vertical" />
              </section>
            </div>
          </el-collapse-item>

          <el-collapse-item name="tools">
            <template #title><span class="collapse-title">工具</span></template>
            <section class="resource-panel">
              <div class="resource-title">
                <div>
                  <strong>内置工具</strong>
                  <small>编码会话下一轮请求会按这里的开关注册工具</small>
                </div>
              </div>
              <el-input v-model="toolQuery" clearable placeholder="搜索工具" />
              <div class="option-grid compact-options">
                <label v-for="row in filteredTools" :key="row.name" class="option-copy tool-option">
                  <el-switch :model-value="config.tools[row.name] !== false" @update:model-value="enabled => setToolEnabled(row.name, enabled)" />
                  <span>
                    <strong>{{ row.name }}</strong>
                    <small>{{ row.description }}</small>
                  </span>
                </label>
              </div>
            </section>
            <section class="resource-panel">
              <div class="resource-title">
                <div>
                  <strong>插件函数</strong>
                  <small>由已安装插件提供的 Function Calling，注册方式和 Agent 管理页一致</small>
                </div>
                <el-checkbox v-model="config.plugin_functions_include_new">包含新增</el-checkbox>
              </div>
              <div class="plugin-toolbar">
                <el-input v-model="pluginQuery" clearable placeholder="搜索函数名称或描述" />
                <el-button size="small" @click="selectVisiblePlugins">全选当前结果</el-button>
                <el-button size="small" @click="clearVisiblePlugins">取消当前结果</el-button>
              </div>
              <el-collapse class="plugin-groups">
                <el-collapse-item v-for="group in visiblePluginGroups" :key="group.pluginKey" :name="group.pluginKey">
                  <template #title>
                    <div class="plugin-group-head">
                      <span class="plugin-title">{{ group.pluginName }} <small>{{ pluginGroupCount(group) }} / {{ group.functions.length }}</small></span>
                      <span>
                        <el-button size="small" link @click.stop="selectPluginGroup(group)">全选</el-button>
                        <el-button size="small" link @click.stop="clearPluginGroup(group)">取消全选</el-button>
                      </span>
                    </div>
                  </template>
                  <el-checkbox-group :model-value="selectedPluginKeys" class="option-grid plugin-options" @change="setSelectedPluginKeys">
                    <el-checkbox v-for="item in group.functions" :key="item.key" :value="item.key">
                      <span class="option-copy">
                        <strong>{{ item.name }}</strong>
                        <small>{{ item.description }}</small>
                      </span>
                    </el-checkbox>
                  </el-checkbox-group>
                </el-collapse-item>
              </el-collapse>
              <el-empty v-if="!pluginFunctionGroups.length" description="当前项目没有可注册的插件函数" :image-size="48" />
            </section>
          </el-collapse-item>

          <el-collapse-item name="view">
            <template #title><span class="collapse-title">多模态</span></template>
            <p class="tab-copy">启用后，编码会话会注册 view_image / view_audio / view_video / view_files，并把资源交给指定 Agent 分析。</p>
            <div class="resource-grid">
              <label v-for="row in viewRows" :key="row.key" class="resource-row">
                <span>{{ row.label }}</span>
                <div class="view-resource-fields">
                  <el-switch v-model="config.view_resources[row.key].enabled" />
                  <small>{{ row.tool }}</small>
                  <el-select v-model="config.view_resources[row.key].agent_id" clearable placeholder="选择分析 Agent" :disabled="!config.view_resources[row.key].enabled">
                    <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
                  </el-select>
                </div>
              </label>
            </div>
          </el-collapse-item>

          <el-collapse-item name="skills">
            <template #title><span class="collapse-title">技能</span></template>
            <section class="resource-panel">
              <div class="resource-title">
                <div>
                  <strong>Skills</strong>
                  <small>按需向上下文注入技能说明</small>
                </div>
                <span>
                  <el-checkbox v-model="config.resources.skills_include_new">包含新增</el-checkbox>
                  <el-checkbox v-model="config.skill_prompt_enabled">注入技能提示</el-checkbox>
                </span>
              </div>
              <el-input v-model="skillQuery" clearable placeholder="搜索 Skill" />
              <el-checkbox-group :model-value="selectedSkillIds()" class="option-grid compact-options" @change="setSelectedSkillIds">
                <el-checkbox
                  v-for="skill in filteredSkills"
                  :key="skill.name"
                  :value="skill.name"
                  :disabled="skill.available === false"
                >
                  <span class="option-copy">
                    <strong>{{ skill.name }}</strong>
                    <small>{{ skill.description || skill.fail_reason || '无描述' }}</small>
                  </span>
                </el-checkbox>
              </el-checkbox-group>
            </section>
          </el-collapse-item>
        </el-collapse>
      </el-scrollbar>
      <el-empty v-else class="environment-empty" description="创建一个编码环境后即可绑定到会话" />
    </div>
  </PageShell>
</template>
