<script setup>
import ResourceToolTimeout from "../components/ResourceToolTimeout.vue";
import { computed, ref, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../bridge/jcefBridge";
import { Search } from "@element-plus/icons-vue";
import PageShell from "../components/common/PageShell.vue";
const rows = ref({}),
  visible = ref(false),
  key = ref(""),
  creating = ref(false),
  busy = ref(false),
  models = ref([]),
  agents = ref([]),
  tools = ref([]),
  skills = ref([]);
const query = ref("");
const filtered = computed(() =>
  Object.entries(rows.value)
    .map(([id, item]) => ({ id, ...item }))
    .filter((item) =>
      `${item.id} ${item.name} ${modelLabel(item.model)}`
        .toLowerCase()
        .includes(query.value.toLowerCase()),
    ),
);
const form = ref({});
const fresh = () => ({
  name: "",
  description: "",
  model: null,
  view_resources: Object.fromEntries(
    ["image", "audio", "video", "file"].map((key) => [
      key,
      { enabled: false, agent_id: null, timeout_secs: null },
    ]),
  ),
  persona: Object.fromEntries(
    ["memory", "behavior_habits", "soul", "profile", "guardrails"].flatMap(
      (key) => [
        [key, ""],
        [`${key}_max_chars`, 512],
      ],
    ),
  ),
  system_prompt: "",
  tools: ["read_project_files", "find_project_files", "search_project_text"],
  skills: [],
  runtime_params: {
    include_history: false,
    max_history_messages: null,
    tool_call_retention_rounds: null,
  },
  max_runtime_secs: null,
  access_mode: "read_only",
  bash_enabled: false,
});
async function load() {
  try {
    const [entries, catalog] = await Promise.all([
      api("subagents.list", { scope: "project" }),
      api("agents.catalog"),
    ]);
    rows.value = entries;
    tools.value = catalog.tools;
    skills.value = catalog.skills;
    agents.value = catalog.agents;
    models.value = catalog.providers.filter(p=>p.enabled!==false).flatMap((p) =>
      p.models.filter(m=>m.enabled!==false).map((m) => ({
        id: m.id,
        reference: { provider_id: p.id, model_id: m.id },
        label: `${p.name} / ${m.name} (${m.alias})`,
      })),
    );
  } catch (e) {
    ElMessage.error(e.message || String(e));
  }
}
const selectedModelId = computed({
  get: () => form.value.model?.model_id ?? null,
  set: (id) => {
    form.value.model =
      models.value.find((item) => item.id === id)?.reference ?? null;
  },
});
const resourceKinds = [
  ["image", "图片"],
  ["audio", "音频"],
  ["video", "视频"],
  ["file", "文件"],
];
const personas = [
  ["memory", "专业记忆"],
  ["behavior_habits", "工作方法"],
  ["soul", "角色设定"],
  ["profile", "能力画像"],
  ["guardrails", "边界约束"],
];
function modelLabel(reference) {
  return (
    models.value.find((item) => item.id === reference?.model_id)?.label ||
    "未选择模型"
  );
}
function edit(id) {
  creating.value = !id;
  key.value = id || "";
  form.value = {
    ...fresh(),
    ...(id ? JSON.parse(JSON.stringify(rows.value[id])) : {}),
    view_resources: Object.fromEntries(
      Object.entries(fresh().view_resources).map(([key, defaults]) => [
        key,
        { ...defaults, ...rows.value[id]?.view_resources?.[key] },
      ]),
    ),
    persona: { ...fresh().persona, ...rows.value[id]?.persona },
    runtime_params: {
      ...fresh().runtime_params,
      ...rows.value[id]?.runtime_params,
    },
  };
  visible.value = true;
}
async function save() {
  busy.value = true;
  try {
    await api("subagent.save", {
      scope: "project",
      id: key.value,
      definition: form.value,
      create: creating.value,
    });
    visible.value = false;
    await load();
    ElMessage.success("项目 SubAgent 已保存");
  } catch (e) {
    ElMessage.error(e.message || String(e));
  } finally {
    busy.value = false;
  }
}
async function remove(id) {
  try {
    await ElMessageBox.confirm(`删除项目 SubAgent ${id}？`, "删除", {
      type: "warning",
    });
    await api("subagent.delete", { scope: "project", id });
    await load();
    ElMessage.success("已删除");
  } catch (e) {
    if (e !== "cancel" && e !== "close")
      ElMessage.error(e.message || String(e));
  }
}
onMounted(load);
</script>
<template>
  <PageShell class="resource-management" title="项目 SubAgents">
    <template #actions
      ><el-button @click="load"
        ><el-icon><Refresh /></el-icon>刷新</el-button
      ><el-button type="primary" @click="edit(null)"
        ><el-icon><Plus /></el-icon>新增项目 SubAgent</el-button
      ></template
    >
    <div class="resource-workspace">
      <div class="management-toolbar">
        <div class="toolbar-heading">
          <el-icon><Cpu /></el-icon><strong>项目子代理</strong
          ><span class="count-label">{{ Object.keys(rows).length }}</span>
        </div>
        <el-input
          v-model="query"
          class="management-search"
          :prefix-icon="Search"
          clearable
          placeholder="搜索名称、标识或模型"
        />
      </div>
      <div class="resource-location">
        <el-icon><FolderOpened /></el-icon><span>.jtools/subAgents.json</span
        ><span class="location-note">仅当前项目可用 · 默认启用</span>
      </div>
      <div class="management-table">
        <el-table :data="filtered" height="100%" row-key="id">
          <el-table-column label="子代理" min-width="190"
            ><template #default="{ row }"
              ><div class="resource-name">
                <el-icon><Cpu /></el-icon>
                <div>
                  <strong>{{ row.name || row.id }}</strong
                  ><small>{{ row.id }}</small>
                </div>
              </div></template
            ></el-table-column
          >
          <el-table-column
            label="任务描述"
            min-width="220"
            show-overflow-tooltip
            ><template #default="{ row }"
              ><span class="secondary-text">{{
                row.description || "未设置描述"
              }}</span></template
            ></el-table-column
          >
          <el-table-column label="模型" min-width="160" show-overflow-tooltip
            ><template #default="{ row }">{{
              modelLabel(row.model)
            }}</template></el-table-column
          >
          <el-table-column label="权限" width="125"
            ><template #default="{ row }"
              ><span class="permission-label"
                ><el-icon
                  ><Lock v-if="row.access_mode !== 'workspace_write'" /><Edit
                    v-else /></el-icon
                >{{
                  row.access_mode === "workspace_write" ? "工作区读写" : "只读"
                }}</span
              ></template
            ></el-table-column
          >
          <el-table-column label="操作" width="125" fixed="right"
            ><template #default="{ row }"
              ><div class="table-actions">
                <el-button link @click="edit(row.id)">编辑</el-button
                ><el-button link type="danger" @click="remove(row.id)"
                  >删除</el-button
                >
              </div></template
            ></el-table-column
          >
          <template #empty
            ><div class="resource-empty">
              <el-icon><Cpu /></el-icon
              ><strong>{{
                query ? "没有匹配的子代理" : "尚未配置项目子代理"
              }}</strong
              ><span>{{
                query
                  ? "尝试其他名称或模型"
                  : "创建专用子代理，处理审查、检索等项目任务。"
              }}</span>
            </div></template
          >
        </el-table>
      </div>
      <div class="management-footer">
        <span>{{ filtered.length }} 个子代理 · 项目范围</span
        ><span>全局定义在 Agent 管理中维护</span>
      </div>
    </div>
    <el-dialog
      v-model="visible"
      class="resource-dialog"
      :title="creating ? '新建项目 SubAgent' : '编辑项目 SubAgent'"
      width="820px"
      top="4vh"
      append-to-body
      destroy-on-close
    >
      <el-form label-position="top" class="resource-editor">
        <section class="editor-section">
          <header>
            <strong>基本信息</strong
            ><span>使用全局模型，定义仅保存在当前项目</span>
          </header>
          <div class="editor-grid">
            <el-form-item label="项目 key"
              ><el-input
                v-model="key"
                :disabled="!creating"
                placeholder="如 code-review" /></el-form-item
            ><el-form-item label="名称"
              ><el-input v-model="form.name" placeholder="如代码审查"
            /></el-form-item>
          </div>
          <el-form-item label="任务描述"
            ><el-input
              v-model="form.description"
              placeholder="描述此子代理适合处理的任务" /></el-form-item
          ><el-form-item label="模型"
            ><el-select
              v-model="selectedModelId"
              filterable
              placeholder="选择模型"
              ><el-option
                v-for="item in models"
                :key="item.id"
                :value="item.id"
                :label="item.label" /></el-select
          ></el-form-item>
        </section>
        <section class="editor-section">
          <header>
            <strong>任务指令</strong><span>定义角色、处理方式与输出要求</span>
          </header>
          <el-form-item label="系统提示词"
            ><el-input
              v-model="form.system_prompt"
              type="textarea"
              :rows="5"
              resize="vertical"
              placeholder="输入此子代理的工作指令"
          /></el-form-item>
        </section>
        <section class="editor-section">
          <header>
            <strong>能力与权限</strong
            ><span>子任务继承当前工作区与 MCP 授权</span>
          </header>
          <div class="editor-grid">
            <el-form-item label="权限"
              ><el-select
                v-model="form.access_mode"
                @change="
                  form.access_mode === 'read_only' &&
                  (form.bash_enabled = false)
                "
                ><el-option value="read_only" label="只读" /><el-option
                  value="workspace_write"
                  label="工作区读写" /></el-select></el-form-item
            ><el-form-item label="Bash"
              ><div class="permission-control">
                <el-switch
                  v-model="form.bash_enabled"
                  :disabled="form.access_mode !== 'workspace_write'"
                /><span>允许执行命令</span>
              </div>
              <small class="field-hint"
                >同时需要在工具列表中选中 bash</small
              ></el-form-item
            >
          </div>
          <el-form-item label="工具"
            ><el-checkbox-group v-model="form.tools" class="editor-tool-grid"
              ><el-checkbox v-for="name in tools" :key="name" :value="name"
                ><span :title="name">{{ name }}</span></el-checkbox
              ></el-checkbox-group
            ></el-form-item
          ><el-form-item label="全局 Skills 授权"
            ><el-select
              v-model="form.skills"
              multiple
              filterable
              placeholder="选择此子代理可用的全局 Skills"
              ><el-option
                v-for="name in skills"
                :key="name"
                :value="name"
                :label="name" /></el-select
            ><small class="field-hint"
              >项目 Skills 自动加载</small
            ></el-form-item
          >
        </section>
        <section class="editor-section">
          <header>
            <strong>多模态资源处理</strong><span>模型原生能力优先，以下超时用于委派处理</span>
          </header>
          <div class="editor-grid">
            <el-form-item
              v-for="[kind, label] in resourceKinds"
              :key="kind"
              :label="label"
              ><div class="project-resource-field">
                <el-switch
                  v-model="form.view_resources[kind].enabled"
                /><el-select
                  v-model="form.view_resources[kind].agent_id"
                  :disabled="!form.view_resources[kind].enabled"
                  clearable
                  placeholder="选择处理 Agent"
                  ><el-option
                    v-for="agent in agents"
                    :key="agent.id"
                    :value="agent.id"
                    :label="agent.name"
                /></el-select>
                <ResourceToolTimeout v-model="form.view_resources[kind].timeout_secs" />
                </div
            ></el-form-item>
          </div>
        </section>
        <section class="editor-section">
          <header>
            <strong>角色与人格</strong><span>在子代理每次执行时注入</span>
          </header>
          <div class="project-persona-grid">
            <el-form-item
              v-for="[kind, label] in personas"
              :key="kind"
              :label="label"
              ><el-input
                v-model="form.persona[kind]"
                type="textarea"
                :rows="3"
                resize="vertical" />
              <div class="persona-limit">
                <span>最大字符数（0 为不限）</span
                ><el-input-number
                  v-model="form.persona[`${kind}_max_chars`]"
                  :min="0"
                  controls-position="right"
                /></div
            ></el-form-item>
          </div>
        </section>
        <section class="editor-section">
          <header><strong>运行限制</strong></header>
          <el-form-item label="包含历史"
            ><el-switch v-model="form.runtime_params.include_history" /><small
              class="field-hint"
              >仅当前会话中同一子代理的已完成任务，包括工具调用记录。</small
            ></el-form-item
          >
          <div class="editor-grid">
            <el-form-item label="最大历史对话轮数"
              ><el-input-number
                v-model="form.runtime_params.max_history_messages"
                :disabled="!form.runtime_params.include_history"
                :min="1"
                controls-position="right"
                placeholder="不限制轮数"
            /></el-form-item>
            <el-form-item label="最大运行秒数"
              ><el-input-number
                v-model="form.max_runtime_secs"
                controls-position="right"
                :min="1"
                placeholder="使用工具上限" /></el-form-item
            ><el-form-item label="工具结果保留轮次"
              ><el-input-number
                v-model="form.runtime_params.tool_call_retention_rounds"
                controls-position="right"
                :min="0"
                placeholder="可选"
            /></el-form-item>
          </div>
        </section>
        <div class="editor-note">
          <el-icon><InfoFilled /></el-icon
          ><span>子任务只使用当前项目资源，也不能再次调用子代理。</span>
        </div> </el-form
      ><template #footer
        ><el-button @click="visible = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="save"
          >保存</el-button
        ></template
      >
    </el-dialog>
  </PageShell>
</template>

<style scoped>
.project-resource-field { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:center; gap:8px; width:100%; }
</style>
