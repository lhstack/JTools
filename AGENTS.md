# AGENTS.md

## 项目概述

JTools（仓库名 tools）是面向 JetBrains IDE 的轻量级插件容器与智能体工作台。核心能力包括：热插拔插件管理、Agent 对话、供应商/模型配置、Skills、MCP、附件多模态输入，以及 IDE 项目文件读写等内置工具。

主插件模块在 `plugin/`，SDK 在 `sdk/`，示例在 `plugin-example/`。Agent 前端为 `plugin/agent-web`（Vue 3 + Element Plus + JCEF bridge），构建产物位于 `plugin/src/main/resources/agent-web/`。

## 工程环境与主要工具

- JDK：17+
- Gradle：8.x（Kotlin DSL）
- Kotlin：2.2.10
- IntelliJ Platform：251–263.*（构建配置当前为 `intellijIdeaCommunity("2025.2")`）
- 前端：Vue 3、Vite、Element Plus、pnpm
- 持久化：SQLite + MyBatis-Plus + HikariCP
- HTTP：OkHttp；MCP：官方 Java SDK

## 目录与模块结构

```
JTools/
├── plugin/                 # 主插件（UI、Agent、DB、工具）
│   ├── agent-web/          # Agent Web 前端源码
│   └── src/main/kotlin/com/lhstack/tools/
│       ├── agent/          # 对话面板、附件、运行时编排
│       ├── agent/model/    # LLM、工具、供应商客户端
│       ├── db/             # 实体、Mapper、Service
│       ├── plugins/        # 热插拔插件容器
│       └── ...
├── sdk/                    # 插件开发 SDK
├── plugin-example/         # 示例插件
└── docs/                   # 设计/计划文档
```

## 分层架构与依赖方向

- **UI 层**（`AgentChatPanel`、JCEF `AgentChatBrowser`、`agent-web`）：只做展示与命令分发，不持有供应商/模型业务状态。
- **编排层**（`AgentChatPanel` 队列、`AgentRunService`）：会话绑定 Agent，经 `AgentRuntime` 执行，历史以 `model_request_logs` 为唯一数据源。
- **运行时层**（`agent/model`）：请求构建、流式解析、工具调用、权限。
- **工具层**（`agent/model/tools`）：工作区路径约束（`WorkspaceTools`）、IDE 项目工具、Bash、WebFetch 等；路径必须落在工作区内。
- **持久化层**（`db/service`）：Agent、会话、配置、日志等 CRUD；`SettingService` 为 key/value 全局配置。
- **插件容器层**（`plugins`）：热插拔与工具暴露。

依赖方向：UI → 编排 → Runtime/Tools → DB/Workspace；禁止 UI 直连供应商状态。

## 构建、测试和验证方式

- 构建：Gradle IntelliJ Platform 插件任务（`plugin/build.gradle.kts`）。
- 前端：在 `plugin/agent-web` 执行 `pnpm build`，产物需同步到 `plugin/src/main/resources/agent-web/`（`app.js` / `app.css` / `index.html`）。
- 测试：`plugin/src/test/kotlin`，JUnit 5；附件/历史等逻辑以纯函数/object 单测为主。
- 未确认：CI 流水线细节与完整 `runIde` 本地脚本约定。

## 项目编码约定

- 语言：Kotlin 为主；前端 Vue 3 `<script setup>`。
- 包名：`com.lhstack.tools.*`。
- 命名：类/文件 PascalCase；方法 camelCase；支持类多以 `*Support` / `*Service` / `*Tools` 命名。
- 状态与 DTO：`data class` + Gson `@SerializedName` 与 Web 侧字段对齐。
- UI 与宿主通信：Web 通过 `invoke('command.type', payload)`，宿主 `handleBrowserCommand` 分发。
- 注释：中文说明职责边界；强调「唯一数据源」「不做旧结构兼容」。
- 最小改动：只改目标相关路径；避免无关兼容与默认兜底掩盖错误。

## 错误处理约定

- 边界校验：工具路径非法、越界工作区等抛 `ToolException` / `IllegalArgumentException`，消息明确。
- 配置解析：非法类型直接报错（如 `SettingService.optionalUsizeSetting`）。
- 对话队列：取消与晚到异常不得重建已删除历史；失败走显式 error 路径。
- 禁止：静默吞错、假成功、无契约 fallback。

## 版本控制信息

- Git 仓库；近期作者：`lhstack <lhstack@foxmail.com>`。
- 版本以 `plugin/build.gradle.kts` 与 README 版本日志为准（如 v1.1.4.7）。

## 有证据支持的用户编码习惯

- 将可单测逻辑抽到独立 `object`/`Support`（如 `AgentAttachmentPresentationSupport`、`AgentInputShortcutSupport`），UI 类保持编排。
- 测试使用 JUnit 5 + `kotlin.test` 断言，用例名偏行为描述（backtick）。
- 中文用户可见文案与对话框说明；实现注释说明职责与数据源。
- 前端按钮/命令与后端 `when (command.type)` 字符串一一对应。
- 提交信息：`feat`/`fix`/`优化` 与版本 changelog 同步更新。

## 当前无法确认的事项

- 前端是否在 Gradle 中自动构建并拷贝，还是手动 `pnpm build` 后提交 `resources/agent-web`。
- 完整 ProGuard/发布流水线在本机与 CI 上的统一方式。
- Agent 对话「文件上下文」开关的历史产品口径（本仓库此前无该功能实现）。
