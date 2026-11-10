# 项目与全局资源

环境保留启用／停用。编码会话每轮装配当前环境授权的全局资源，再追加会话绑定项目的 `.jtools` 资源。项目配置默认启用，不接受 enabled 字段。全局 MCP、Agent 继续存在 SQLite，全局 Skills 继续使用 JTools 主目录下的 skills。

## 项目结构

```text
.jtools/
  mcpServer.json
  subAgents.json
  skills/
    code-review/
      SKILL.md
      references/
```

不向父目录或其他项目搜索。无配置文件表示无项目资源；文件损坏、重复 JSON key、未知字段、无效模型引用直接报错。读取不会创建空配置。修改以临时文件和原子替换发布，并检查操作期间的外部文件变化；无跨编辑器事务保证。

### mcpServer.json

```json
{
  "version": 1,
  "mcpServers": {
    "project-docs": {
      "transport": "stdio",
      "command": "node",
      "args": ["./tools/docs-mcp/server.js"],
      "env": {}
    },
    "project-api": {
      "transport": "streamable_http",
      "url": "http://127.0.0.1:9000/mcp",
      "headers": {}
    }
  }
}
```

支持 stdio、sse、streamable_http。stdio 子进程 cwd 固定为当前项目根目录，不改变 IDE 进程 cwd。配置中的环境变量和 headers 是真实配置，文件可能包含凭据，应自行决定是否提交版本控制。

### subAgents.json

```json
{
  "version": 1,
  "subAgents": {
    "reviewer": {
      "name": "代码审查",
      "description": "检查项目变更",
      "model": { "provider_id": 1, "model_id": 2 },
      "system_prompt": "读取相关代码并给出审查结果。",
      "tools": ["read_project_files", "find_project_files", "search_project_text"],
      "skills": [],
      "access_mode": "read_only",
      "bash_enabled": false,
      "max_runtime_secs": 300
    }
  }
}
```

model 使用 provider_id/model_id 明确引用全局供应商和模型。同名或同 alias 不影响选择；不匹配、缺失、停用时报错。旧 alias 字符串格式不再支持，需在项目 SubAgents 编辑器重新选择模型。项目定义不保存数据库 Agent ID 或模型凭据。权限为 read_only/workspace_write；只读不能启用 Bash。子任务继承当前项目、父轮资源来源与 MCP 授权，全局技能还受子 Agent 自身授权限制；项目 Skills 自动追加。子任务无其他会话历史，不能通过 subagent_run 或 CLI agent.run 再创建子任务。CLI 写操作在只读子任务中被拒绝。

runtime_params 支持 include_history、max_history_messages、tool_call_retention_rounds。历史仅取当前会话内同一带 scope 身份子代理已完成的任务及其工具调用，不读取其他会话或项目。max_runtime_secs 为正整数，支持取消和超时清理。view_resources 和 persona 沿用全局 Agent 对应的 snake_case 配置结构，在执行时生效。

### Skills

沿用现有 SKILL.md 元数据与目录规范。skills_list 可不指定 scope，返回双来源清单；skills_view 必须指定 scope 与 name，同名技能可以同时存在。不接受越界路径及技能资源符号链接。

## CLI

mcp.list、skill.list 无参返回所有可用来源；其他资源命令在 arguments 中显式提供 scope=global/project，不接受 project、project_path、cwd 来切换目标项目。

```json
{"command":"mcp.list"}
{"command":"mcp.create","arguments":{"scope":"project","name":"docs","transport":"stdio","command":"node","args":["./tools/mcp.js"]}}
{"command":"mcp.call","arguments":{"scope":"project","id":"docs","tool":"search","arguments":{"query":"build"}}}
{"command":"skill.create","arguments":{"scope":"project","name":"review","description":"项目审查"}}
{"command":"skill.write","arguments":{"scope":"project","name":"review","path":"SKILL.md","content":"---\ndescription: review\n---\n审查指令"}}
{"command":"subagent.create","arguments":{"scope":"project","id":"reviewer","definition":{"model":{"provider_id":1,"model_id":2},"tools":["read_project_files"],"access_mode":"read_only"}}}
```

- mcp.list 无需 scope，返回当前授权全局及项目服务；其他 mcp.*：get/create/update/delete/test/tools/resources/read_resource/call；global id 为数据库 ID，project id 为 JSON 对象 key。项目不允许 enabled。
- skill.list 无需 scope，返回环境授权全局技能及项目技能；其他 skill.*：get/files/read/create/import/write/create_file/create_directory/delete/delete_entry；路径字段使用 path、parent_path、source_path。
- subagent.*：list/get/create/update/delete；项目 create/update 使用 id+definition；全局沿用 agent.* 的字段与数据库逻辑（provider_id/model_id/extra_prompt/ext_config 等）。
- 子任务执行使用 subagents_list/subagent_run；run 参数为 scope、agent_id（全局 ID 字符串或项目 key）、title、task、可选 context。
- `cli doc` 和 `<command> --help` 包含参数说明。MCP 与 Skills 工具每次调用重新读取资源和当前环境授权；新增、修改、删除、启停和授权变更在后续工具调用中立即生效。提示词内清单为当轮初始观察，实时清单以 mcp.list / skill.list 为准。

## 管理界面与继承选择

外层 MCP 管理提供全局／项目切换、CRUD、异步连接测试和工具查询。Skills 管理复用目录编辑器并增加范围切换。项目 SubAgents 提供模型、提示词、工具、权限等表单；全局在 Agent 管理维护。

环境选择区仅管理全局 MCP／Skills；项目资源不在该处重复开关。工具、Skills、插件函数、MCP 的有效选择按“显式值优先，否则跟随包含新增”。恢复继承清空显式值；打开后直接保存不会将继承项物化为显式禁用，搜索选择不影响其他项。环境启停影响会话能否运行，不等同于项目资源启停。

## 验证

```bash
node --test plugin/agent-web/tests/*.test.mjs
pnpm --dir plugin/agent-web build
./gradlew :jtools:compileKotlin
./gradlew :jtools:test --tests '*ProjectResourceStoreTest' --tests '*CodingRuntimeSupportTest' --tests '*CliToolTest' -x :sdk:prepareJarSearchableOptions -x :sdk:buildSearchableOptions
```

测试排除项是 SDK searchable-options 打包任务，本地该任务缺少 META-INF/plugin.xml；不排除代码编译和测试。MCP cwd 测试使用系统 Python 创建真实 stdio MCP 服务器，不依赖外部在线服务。浏览器页面与 bridge 契约使用隔离数据验证；完整 IDE+真实模型子任务联调需在插件重载后执行。


### 浏览器页面契约验收

`tests/resource-ui.mjs` 使用隔离的 JCEF bridge 数据验证页面 scope、CRUD 参数和继承选择，不接触真实数据库或项目文件。调用者需提供已安装的 playwright-core（通过 JTOOLS_BROWSER_MODULE_BASE 指向其 package.json 所在父项目），以及 BROWSER_EXECUTABLE。可选 VISUAL_ARTIFACTS 保存截图。

### 本地打包限制

当前 buildPlugin 的 ProGuard 发布规则包含旧机器 Gradle 缓存绝对路径。使用完整 JDK 后仍因该路径不存在而失败；本次未改发布脚本，不能将旧的 proguarded JAR/ZIP 作为本次产物。源码编译、instrumented JAR 和前端资源构建可独立验证。


### 多模态超时

Agent 管理 → 模型与运行参数 → 最大运行秒数控制该 Agent 作为多模态处理器时的运行上限；未配置为 120 秒，配置 3000 即为 3000 秒。工具执行器预留 10 秒返回内部超时结果；资源运行会独立取消，不取消父对话。全局设置 → 模型网络 → 模型 HTTP 请求超时单独控制一次网络请求，0 表示不限时；较小的非零值仍会先触发 HTTP 超时。
