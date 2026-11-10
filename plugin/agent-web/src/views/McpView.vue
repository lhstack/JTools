<script setup>
import { ref, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../bridge/jcefBridge";
import { Search } from "@element-plus/icons-vue";
import { parseEnvironmentImport } from "../mcpEnvironmentImport.js";
import PageShell from "../components/common/PageShell.vue";
const scope = ref("global"),
  rows = ref([]),
  query = ref(""),
  visible = ref(false),
  saving = ref(false),
  testing = ref(new Set()),
  loadingTools = ref(new Set()),
  importVisible = ref(false),
  importText = ref(""),
  result = ref(null),
  resultVisible = ref(false);
const form = ref({}),
  originalId = ref(null);
const fresh = () => ({
  name: "",
  transport: "stdio",
  command: "",
  args: "[]",
  env: "{}",
  url: "",
  headers: "{}",
  enabled: true,
});
const filtered = computed(() =>
  rows.value.filter(
    (x) =>
      !query.value ||
      `${x.name} ${x.transport}`
        .toLowerCase()
        .includes(query.value.toLowerCase()),
  ),
);
async function load() {
  try {
    rows.value = await api("mcp.list", { scope: scope.value });
  } catch (e) {
    ElMessage.error(e.message || String(e));
  }
}
function edit(item) {
  originalId.value = item?.id ?? null;
  form.value = item
    ? {
        ...item,
        args: JSON.stringify(item.args, null, 2),
        env: JSON.stringify(item.env, null, 2),
        headers: JSON.stringify(item.headers, null, 2),
      }
    : fresh();
  visible.value = true;
}
function json(text, array) {
  const value = JSON.parse(text);
  if (
    array
      ? !Array.isArray(value)
      : !value || typeof value !== "object" || Array.isArray(value)
  )
    throw Error(array ? "args 必须为数组" : "必须为 JSON 对象");
  return value;
}
async function save() {
  saving.value = true;
  try {
    const f = form.value;
    const payload = {
      scope: scope.value,
      name: f.name,
      transport: f.transport,
      command: f.transport === "stdio" ? f.command : null,
      args: json(f.args, true),
      env: json(f.env, false),
      url: f.transport === "stdio" ? null : f.url,
      headers: json(f.headers, false),
    };
    if (originalId.value !== null) payload.id = originalId.value;
    if (scope.value === "global") payload.enabled = f.enabled;
    await api("mcp.save", payload);
    visible.value = false;
    await load();
    ElMessage.success("MCP 已保存");
  } catch (e) {
    ElMessage.error(e.message || String(e));
  } finally {
    saving.value = false;
  }
}
async function remove(item) {
  try {
    await ElMessageBox.confirm(`删除 ${item.name}？`, "删除 MCP", {
      type: "warning",
    });
    await api("mcp.delete", { scope: scope.value, id: item.id });
    await load();
    ElMessage.success("已删除");
  } catch (e) {
    if (e !== "cancel" && e !== "close")
      ElMessage.error(e.message || String(e));
  }
}
function inspectionKey(item) {
  return `${scope.value}:${item.id}`;
}
function openEnvironmentImport() {
  importText.value = "";
  importVisible.value = true;
}
function importEnvironment() {
  try {
    const incoming = parseEnvironmentImport(importText.value);
    form.value.env = JSON.stringify(
      { ...json(form.value.env, false), ...incoming },
      null,
      2,
    );
    importVisible.value = false;
    ElMessage.success(
      `已导入 ${Object.keys(incoming).length} 个环境变量，保存后生效`,
    );
  } catch (e) {
    ElMessage.error(e.message || String(e));
  }
}
async function inspect(item, command) {
  const key = inspectionKey(item);
  const active = command === "mcp.test" ? testing : loadingTools;
  if (active.value.has(key)) return;
  active.value.add(key);
  try {
    const payload = { scope: scope.value, id: item.id };
    if (command === "mcp.test") {
      const job = await api(command, payload);
      ElMessage.info(`${item.name}：正在后台测试连接`);
      let response;
      do {
        await new Promise((resolve) => setTimeout(resolve, 200));
        response = await api("mcp.poll", { jobId: job.jobId });
      } while (!response.done);
      ElMessage.success(`${item.name}：连接测试通过`);
    } else {
      result.value = await api(command, payload);
      resultVisible.value = true;
    }
  } catch (e) {
    ElMessage.error(`${item.name}：${e.message || String(e)}`);
  } finally {
    active.value.delete(key);
  }
}
onMounted(load);
</script>
<template>
  <PageShell class="resource-management" title="MCP 管理">
    <template #actions
      ><el-button @click="load"
        ><el-icon><Refresh /></el-icon>刷新</el-button
      ><el-button type="primary" @click="edit(null)"
        ><el-icon><Plus /></el-icon>新建 MCP</el-button
      ></template
    >
    <div class="resource-workspace">
      <div class="management-toolbar">
        <el-radio-group v-model="scope" class="scope-selector" @change="load"
          ><el-radio-button value="global">全局</el-radio-button
          ><el-radio-button value="project"
            >当前项目</el-radio-button
          ></el-radio-group
        >
        <el-input
          v-model="query"
          class="management-search"
          placeholder="搜索服务名称或协议"
          clearable
          :prefix-icon="Search"
        />
      </div>
      <div class="resource-location">
        <el-icon><FolderOpened /></el-icon
        ><span>{{
          scope === "project" ? ".jtools/mcpServer.json" : "全局 MCP 配置"
        }}</span
        ><span class="location-note">{{
          scope === "project"
            ? "仅当前项目可用 · 默认启用"
            : "按编码环境授权使用"
        }}</span>
      </div>
      <div class="management-table">
        <el-table
          :data="filtered"
          height="100%"
          :row-key="(row) => String(row.id)"
        >
          <el-table-column label="服务" min-width="190"
            ><template #default="{ row }"
              ><div class="resource-name">
                <el-icon><Connection /></el-icon>
                <div>
                  <strong>{{ row.name }}</strong
                  ><small>{{
                    scope === "project" ? row.id : "全局服务"
                  }}</small>
                </div>
              </div></template
            ></el-table-column
          >
          <el-table-column label="连接地址 / 命令" min-width="220"
            ><template #default="{ row }"
              ><span
                class="endpoint-text"
                :title="
                  row.transport === 'stdio'
                    ? [row.command, ...(row.args || [])].join(' ')
                    : row.url
                "
                >{{
                  row.transport === "stdio"
                    ? [row.command, ...(row.args || [])].join(" ")
                    : row.url
                }}</span
              ></template
            ></el-table-column
          >
          <el-table-column label="协议" width="148"
            ><template #default="{ row }"
              ><span class="protocol-label">{{
                row.transport === "streamable_http"
                  ? "Streamable HTTP"
                  : row.transport.toUpperCase()
              }}</span></template
            ></el-table-column
          >
          <el-table-column label="状态" width="110"
            ><template #default="{ row }"
              ><span
                class="status-label"
                :class="{ 'is-active': scope === 'project' || row.enabled }"
                ><i />{{
                  scope === "project"
                    ? "默认启用"
                    : row.enabled
                      ? "已启用"
                      : "已停用"
                }}</span
              ></template
            ></el-table-column
          >
          <el-table-column label="操作" width="270" fixed="right"
            ><template #default="{ row }"
              ><div class="table-actions">
                <el-button link @click="edit(row)">编辑</el-button
                ><el-button
                  link
                  :loading="testing.has(inspectionKey(row))"
                  @click="inspect(row, 'mcp.test')"
                  >测试连接</el-button
                ><el-button
                  link
                  :loading="loadingTools.has(inspectionKey(row))"
                  @click="inspect(row, 'mcp.tools')"
                  >工具列表</el-button
                ><el-button link type="danger" @click="remove(row)"
                  >删除</el-button
                >
              </div></template
            ></el-table-column
          >
          <template #empty
            ><div class="resource-empty">
              <el-icon><Connection /></el-icon
              ><strong>{{
                query ? "没有匹配的服务" : "尚未配置 MCP 服务"
              }}</strong
              ><span>{{
                query
                  ? "尝试其他名称或协议"
                  : "新建服务后，可在对话中调用其工具。"
              }}</span>
            </div></template
          >
        </el-table>
      </div>
      <div class="management-footer">
        <span
          >{{ scope === "project" ? "当前项目" : "全局" }} ·
          {{ filtered.length }} 个服务</span
        ><span>MCP · Model Context Protocol</span>
      </div>
    </div>
    <el-dialog
      v-model="visible"
      class="resource-dialog"
      :title="originalId === null ? '新建 MCP 服务' : '编辑 MCP 服务'"
      width="760px"
      top="6vh"
      append-to-body
      destroy-on-close
    >
      <el-form label-position="top" class="resource-editor">
        <section class="editor-section">
          <header>
            <strong>基本信息</strong
            ><span>{{
              scope === "project"
                ? "配置仅在当前项目生效"
                : "配置可由多个编码环境引用"
            }}</span>
          </header>
          <div class="editor-grid">
            <el-form-item label="名称 / 项目 key"
              ><el-input
                v-model="form.name"
                placeholder="如 project-docs" /></el-form-item
            ><el-form-item label="传输协议"
              ><el-select v-model="form.transport"
                ><el-option value="stdio" label="STDIO · 本地进程" /><el-option
                  value="sse"
                  label="SSE · 远程服务" /><el-option
                  value="streamable_http"
                  label="Streamable HTTP · 远程服务" /></el-select
            ></el-form-item>
          </div>
        </section>
        <section class="editor-section">
          <header>
            <strong>连接配置</strong
            ><span>{{
              form.transport === "stdio"
                ? "启动本地 MCP 服务进程"
                : "连接 MCP 服务端点"
            }}</span>
          </header>
          <template v-if="form.transport === 'stdio'"
            ><el-form-item label="可执行命令"
              ><el-input
                v-model="form.command"
                placeholder="node / python3 / 可执行文件路径"
            /></el-form-item>
            <div class="editor-grid">
              <el-form-item label="参数（JSON 数组）"
                ><el-input
                  v-model="form.args"
                  class="config-code"
                  type="textarea"
                  :rows="5"
                  spellcheck="false" /></el-form-item
              ><el-form-item>
                <template #label
                  ><span class="field-label-actions"
                    ><span>环境变量（JSON 对象）</span
                    ><el-button
                      link
                      type="primary"
                      @click="openEnvironmentImport"
                      >批量导入</el-button
                    ></span
                  ></template
                ><el-input
                  v-model="form.env"
                  class="config-code"
                  type="textarea"
                  :rows="5"
                  spellcheck="false"
              /></el-form-item></div></template
          ><template v-else
            ><el-form-item label="URL"
              ><el-input
                v-model="form.url"
                placeholder="https://example.com/mcp" /></el-form-item
            ><el-form-item label="请求头（JSON 对象）"
              ><el-input
                v-model="form.headers"
                class="config-code"
                type="textarea"
                :rows="5"
                spellcheck="false" /></el-form-item
          ></template>
        </section>
        <div class="editor-note">
          <el-icon v-if="scope === 'project'"><InfoFilled /></el-icon
          ><span v-if="scope === 'project'"
            >项目服务默认启用。本地进程的工作目录为当前项目根目录。</span
          ><el-checkbox v-else v-model="form.enabled">启用此服务</el-checkbox>
        </div> </el-form
      ><template #footer
        ><el-button @click="visible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save"
          >保存</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="importVisible"
      class="resource-dialog"
      title="批量导入环境变量"
      width="600px"
      append-to-body
      destroy-on-close
    >
      <div class="environment-import">
        <p>
          每行一项，支持 KEY=value 或 KEY:
          value。名称和值会去除前后空格，同名变量覆盖现有值。
        </p>
        <el-input
          v-model="importText"
          class="config-code"
          type="textarea"
          :rows="10"
          placeholder="API_KEY = your-key&#10;BASE_URL: https://example.com"
          spellcheck="false"
        />
      </div>
      <template #footer
        ><el-button @click="importVisible = false">取消</el-button
        ><el-button type="primary" @click="importEnvironment"
          >导入</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="resultVisible"
      class="resource-dialog"
      title="MCP 检查结果"
      width="800px"
      append-to-body
    >
      <pre class="inspection-result">{{ JSON.stringify(result, null, 2) }}</pre>
    </el-dialog>
  </PageShell>
</template>
