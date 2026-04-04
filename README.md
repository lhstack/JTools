# 🛠️ JTools - 轻量级插件容器

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/com.lhstack.tools"><img src="https://img.shields.io/badge/JetBrains-Marketplace-blue?logo=jetbrains" alt="JetBrains Marketplace"></a>
  <a href="https://github.com/lhstack/JTools/releases"><img src="https://img.shields.io/github/v/release/lhstack/JTools?color=green" alt="Release"></a>
  <a href="https://github.com/lhstack/JTools/blob/main/LICENSE"><img src="https://img.shields.io/github/license/lhstack/JTools" alt="License"></a>
  <a href="https://github.com/lhstack/JTools/issues"><img src="https://img.shields.io/github/issues/lhstack/JTools" alt="Issues"></a>
</p>

<p align="center">
  <b>专为 JetBrains IDE 设计的强大且轻量级的插件管理容器</b>
</p>

<p align="center">
  <a href="#-核心特性">核心特性</a> •
  <a href="#-快速开始">快速开始</a> •
  <a href="#-应用场景">应用场景</a> •
  <a href="#-项目结构">项目结构</a> •
  <a href="#-版本日志">版本日志</a>
</p>

---

## 📖 简介

JTools 通过提供**热插拔功能**彻底改变了插件开发体验，允许开发者在**不重启 IDE** 的情况下安装、更新和卸载插件，显著提升开发效率。

## ✨ 核心特性

| 特性 | 描述 |
|------|------|
| 🔌 **热插拔支持** | 无需重启 IDE 即可即时安装和卸载插件 |
| 📦 **拖拽安装** | 将插件文件拖入面板即可立即安装，右键菜单轻松卸载 |
| 🚀 **多语言开发** | 全面支持 Java、Kotlin 和 JavaScript 插件开发 |
| 📂 **自定义存储** | 可配置插件安装目录，按产品和版本自动隔离 |
| 📋 **集成日志** | 内置日志控制台，支持软换行输出 |
| 🤖 **智能体对话** | 内置智能体入口，支持流式输出、推理块展示与工具调用折叠日志 |
| 🧭 **供应方管理** | 多供应方配置（OpenAI/Anthropic），支持 Base URL、Header、代理 |
| 🎛️ **供应方选择** | 对话中可选供应方，并按供应方维护模型列表与默认模型 |
| 🧩 **供应方模板** | 支持 OpenAI Compatible 接入类型与厂商模板，预置端点和参数建议 |
| 🌍 **广泛供应方覆盖** | 内置 OpenAI、DeepSeek、GLM、OpenRouter、SiliconFlow、DashScope、Gemini、Ollama、Anthropic 模板 |
| 🧰 **函数调用** | 聚合插件与系统工具并增加唯一标识，避免名称冲突 |
| 📦 **插件管理** | 支持本地/URL/批量安装与卸载的工具调用 |
| 🧪 **开发测试** | 开发面板可暴露当前插件实例的工具调用用于调试 |
| 📎 **附件工作流** | 对话支持本地文件和图片附件，具备模型能力校验、剪贴板支持与上传优化 |
| 🖼️ **多模态输入** | 可按模型开启图片、音频、视频、文件附件能力，并自动抽取可读文件文本 |
| 🧠 **Skills 管理** | 支持导入或编辑本地 `SKILL.md` 技能，按会话挂载并暴露资源 |
| 📚 **Skills 资源** | `references/examples/scripts` 等资源可随技能挂载，并通过工具读取 |
| 🛠️ **系统工具套件** | 内置插件管理、文件浏览、技能管理、MCP 管理等 JTools 系统工具 |
| 🎚️ **模型调优** | 提供独立模型调优面板，可配置流式输出、多模态能力和高级参数 |
| 🧾 **参数建议** | 支持保存供应方默认参数与推荐参数，让模型配置更容易复用 |
| 💾 **会话持久化** | 跨项目保留会话、启用技能、草稿附件与当前上下文 |
| 🧠 **运行时状态** | 会话运行时可保留记忆、工具状态与规划上下文，适合多步骤任务 |
| ⚙️ **智能体限制** | 可配置最大工具调用轮次并限制工具返回长度，避免超大上下文 |
| 📂 **文件列表** | 文件列表支持 maxEntries 并返回截断信息 |
| 🔧 **动态 SDK 管理** | 自动解析 IDEA、Maven 和 Gradle 项目的 SDK 依赖 |
| 🌐 **Web 集成** | JavaScript 插件支持灵活的网页集成 |
| 🪟 **多实例支持** | 支持同时打开同一插件的多个实例 |
| 📐 **分割面板视图** | 支持左右面板分割，更好地组织工作空间 |
| 🎯 **全家桶支持** | 兼容 IntelliJ IDEA、WebStorm、PyCharm、GoLand、PhpStorm 等 |

## 🚀 快速开始

### 安装

1. 打开 JetBrains IDE → `Settings/Preferences` → `Plugins`
2. 搜索 **JTools**
3. 点击 `Install` 并重启 IDE

或者从 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.lhstack.tools) 下载安装。

### 使用

1. 从右侧边栏打开 **JTools** 面板
2. 拖放插件文件或使用内置开发工具
3. 开始享受热重载开发体验！

## 🎯 应用场景

- **快速原型开发** - IntelliJ 插件的快速原型开发和测试
- **轻量级工具** - 创建轻量级实用工具，无需完整插件开销
- **Web 工具集成** - 开发集成到 IDE 中的自定义 Web 工具
- **团队工具分发** - 构建团队专用开发工具，便于分发
- **IDE 内 Agent 工作台** - 在同一个面板里管理模型、技能、MCP、附件和工具调用
- **多模型协同接入** - 通过供应方模板快速接入 OpenAI Compatible 服务并做模型级调优

## 🤖 智能体工作台亮点

- **供应方接入更完整** - 现在不只是 OpenAI/Anthropic，还覆盖 DashScope、Gemini、Ollama 和多种 OpenAI Compatible 厂商模板。
- **附件能力更细** - 图片会按上传体积自动优化，可读文本文件会自动抽取内容，附件按钮也会根据模型能力动态显示。
- **Skills 更像本地知识包** - 可以直接导入 `SKILL.md` 目录，连同 `references`、`examples`、`scripts` 一起挂到会话里。
- **工具侧闭环更强** - 智能体不只会调用插件工具，还能调用 JTools 自带的技能管理、MCP 管理、文件浏览和插件运维工具。
- **会话上下文更稳** - 会按项目保留当前会话、技能启用状态、草稿附件和部分运行时上下文，适合连续协作。

## 📁 项目结构

```
JTools/
├── plugin/          # 主插件模块
├── sdk/             # SDK 模块 (供插件开发使用)
├── plugin-example/  # 插件开发示例
└── gradle/          # Gradle 配置
```

## 🔧 开发环境

- **JDK**: 17+
- **Gradle**: 8.x
- **IntelliJ Platform**: 2025.1 - 2025.3.*
- **Kotlin**: 2.2.10

## 📦 版本日志


### v1.1.3.3 (当前版本)
- 🧰 **工具调用卡片** - 将工具日志重构为按调用分组的卡片列表，显示时间、状态与完成计数，执行过程更清晰
- 🔍 **工具详情查看** - 新增参数/返回值弹窗 JSON 查看器，长内容无需挤占对话流即可直接检查
- 🧠 **事件解析稳定性** - 改进推理、工具调用与工具结果 block 的解析和参数序列化，减少调用匹配错乱与渲染兜底不准的问题
- 📦 **渲染状态兼容性** - 新增工具渲染条目状态持久化，并同步对齐 1.1.3.3 的 SDK 版本元数据

### v1.1.3.2
- 📝 **Skills 配置体验** - 调整资源路径编辑区布局，收紧标签与输入框间距，并对齐单行表单高度
- ✅ **工具调用默认值** - OpenAI 和 Anthropic 模型参数面板中的工具调用能力默认勾选
- 🧠 **推理与工具日志** - 补强 Anthropic/OpenAI 的推理内容、工具调用和工具结果渲染兜底，减少只显示结果不显示调用的问题
- 🔌 **原生供应方依赖** - 补充 Anthropic 与 Gemini 原生 SDK 依赖，完善对应供应方运行时支持
- 📂 **文件工具重构** - 文件读取改为 `jtools_get_file_char_count` + `jtools_read_file_chunk` 分段读取，写入改为会话式 `open/append/commit/discard`
- 🛡️ **写入与命令确认** - 文件写入会话与脚本命令执行均在操作前弹窗确认，降低误操作风险
- 🧰 **系统工具增强** - 新增当前项目查询、目录读取、目录创建、项目感知路径解析等系统工具能力

### v1.1.3.1
- 📎 **附件工作流** - 新增模型感知的文件/图片附件能力，支持剪贴板粘贴与媒体上传优化
- 🖼️ **多模态输入** - 新增按模型维度控制图片/音频/视频/文件能力，并可抽取可读文件内容作为上下文
- 🧠 **Skills 管理** - 新增本地 `SKILL.md` 导入、手动编辑、按会话启用与运行时资源挂载
- 📚 **Skills 资源** - 新增资源列举与读取能力，导入技能可暴露 `references`、`examples`、`scripts` 等文件
- 🎚️ **模型调优** - 新增按模型维度配置流式输出、多模态能力与 OpenAI/Anthropic 高级参数
- 🧩 **供应方模板** - 扩展供应方配置，支持 DashScope/OpenAI Compatible/Anthropic/Gemini/Ollama 接入类型、厂商模板与参数建议
- 🛠️ **系统工具套件** - 新增技能增删改查、会话技能开关、MCP 管理等内置工具能力
- 💾 **会话持久化** - 按项目保存当前会话、草稿附件与启用技能状态

### v1.1.3.0
- 🎛️ **模型参数面板** - 新增模型参数面板，支持更细粒度控制
- 🧾 **格式化输出** - 优化响应格式输出，展示更清晰

### v1.1.2.9
- 🧭 **供应方管理** - 新增多供应方配置（OpenAI/Anthropic），支持 Base URL、Header、代理
- 🎛️ **供应方选择** - 对话支持供应方选择，并按供应方管理模型列表/默认模型
- 🔌 **SDK 升级** - OpenAI/Anthropic 调用切换为官方 SDK，完善流式与工具调用

### v1.1.2.8
- 🔌 **MCP 客户端** - 新增外部 MCP 服务支持，覆盖 stdio/SSE/streamable HTTP 传输
- 🧭 **MCP 配置** - 新增多服务管理界面，支持工具/资源/提示列表与刷新
- 📶 **可用性监控** - 后台定时检测并显示状态，失败通过通知提示
- 🧰 **工具栏体验** - 使用 ActionToolbar 图标按钮并修复可用状态切换

### v1.1.2.7
- 🤖 **智能体对话** - 新增会话历史，支持新建/重命名/删除管理
- 🎛️ **模型管理** - 模型选择与管理迁移到对话面板
- ⏹️ **请求控制** - 新增停止按钮可终止当前回复
- 🔗 **Base URL** - 修复首次打开设置时已保存地址被预设覆盖的问题

### v1.1.2.6
- 🤖 **智能体对话** - 新增智能体入口，支持流式输出、推理块展示与工具调用折叠日志
- 🧰 **函数调用** - 聚合插件与系统工具并增加唯一标识，避免名称冲突
- 📦 **插件管理** - 新增本地/URL/批量安装与卸载工具调用
- 🧪 **开发测试** - 开发面板可暴露当前插件实例的工具调用用于调试
- 🎨 **对话体验** - 优化输入体验（软换行、边框、Shift+Enter 发送）并增加重复工具调用拦截
- ⚙️ **智能体限制** - 新增最大工具调用轮次配置，并限制工具返回内容长度以避免超大上下文
- 📂 **文件列表** - jtools_list_files 支持 maxEntries 并返回截断信息，避免超大输出

### v1.1.2.5
- 🧩 **分屏增强** - 支持向右/向下分屏并移动的操作流程
- 🖱️ **拖拽体验** - 同分屏排序与跨分屏拖拽优化，支持拖拽预览与目标高亮
- 🧱 **稳定性** - 修复分屏拖拽导致的内容丢失、灰色区域等问题

### v1.1.2.3
- 🪟 **弹窗重构** - 移除 DialogWrapper 支持，改用 JFrame 实现，提升弹窗稳定性和兼容性
- 🌐 **JS 插件 SDK** - JS 插件新增 JTools SDK 自动注入，提供简化的 API 调用
- 📁 **相对路径支持** - JS 插件现支持标准相对路径加载静态资源，无需协议前缀
- 📚 **API 文档** - 新增 `help()` 函数获取完整的 JS 插件 API 文档
- 🔧 **新增 JS API** - 新增 50+ 个 API（文件操作、剪贴板、对话框、系统命令、通知等）
- 💾 **缓存增强** - 缓存新增 `getOrDefault`、`keys`、`exists`、`size`、`setAll` 方法
- 📝 **类型定义** - 新增"生成类型定义"功能，导出 `jtools-sdk.d.ts` 支持 IDE 代码提示

#### JS 插件 SDK 使用示例
```javascript
// 日志
JTools.log.info("Hello World")

// 文件操作
await JTools.file.read("/path/to/file.txt")
await JTools.file.write("/path/to/file.txt", "content")

// 对话框
const name = await JTools.dialog.input("请输入名称")
const file = await JTools.dialog.chooseFile()

// 缓存
await JTools.cache.global.set("key", "value")
await JTools.cache.project.get("key")

// 通知
JTools.notifyInfo("标题", "内容")

// 获取帮助文档
await JTools.help()
```

### v1.1.2.2
- 🎉 **IDE 兼容性** - 全面支持 IntelliJ IDEA 2025.3 及相关 JetBrains 产品

### v1.1.2.1
- 📂 **存储隔离** - 插件存储目录按 JetBrains 产品类型和版本自动隔离，防止不同 IDE 安装之间的冲突
- 🔧 **SDK 增强** - 新增支持函数，提供 JTools 版本和 JetBrains 产品信息

### v1.1.1
- 🎉 **IDE 兼容性** - 全面兼容 IntelliJ IDEA 2025.2 及以上版本

### v1.1.0
- 🪟 **多实例 SDK** - SDK 完全适配多实例插件支持，实现多个插件窗口间更好的资源管理

### v1.0.9
- 🎯 **JetBrains 全家桶** - 扩展支持整个 JetBrains IDE 系列

### v1.0.8
- 📐 **分割面板** - 插件面板支持分割视图功能（左右分割）
- 📋 **日志控制台** - 新增日志控制台启用/禁用开关

### v1.0.7
- 🪟 **多实例支持** - 插件可通过重载 `supportMultiOpens` 函数支持多实例
- 🔄 **面板重构** - 重构 `showPanel` 和 `closePanel` 函数，解决多实例资源回收问题
- 🐛 **Bug 修复** - 修复拖动安装时 `openProject` 回调中 logger 参数错误
- ✨ **项目关闭处理** - 新增项目关闭时自动调用已打开面板的 `closePanel` 函数

### v1.0.6
- 📝 **JS 模板** - JS 插件开发新增项目模板
- 📦 **JS 打包** - JS 插件开发新增打包功能
- 🔧 **原生开发** - 优化原生插件开发运行逻辑

### v1.0.5
- 🧹 **清理优化** - 优化插件卸载后的清理流程

### v1.0.4
- 📋 **日志控制台体验** - JTools 日志控制台使用软换行模式

### v1.0.3
- 📌 **Since 注解** - SDK 新增 `@Since` 注解
- 📂 **文件操作** - Helper 类新增文件选择和保存对话框函数
- 📝 **编辑器集成** - Helper 新增 `openInEditor` 函数
- 🔧 **工具栏支持** - Helper 新增 `createActionToolbar` 函数
- 🔄 **API 变更** - IPlugin 入参函数名从 `projectLocation` 改为 `locationHash`
- ✨ **操作按钮** - `actionButton` 函数新增 `isSelected` 参数

### v1.0.2
- 🔔 **通知功能** - Helper 类新增 `notify` 函数
- 📋 **系统日志** - Helper 新增 `getSysLogger` 函数
- 📝 **语言文本框** - Helper 新增 `languageTextField` 函数
- 🔍 **兼容性检查** - IPlugin 新增 `support` 函数

### v1.0.1
- 🎨 **图标更新** - 更新多个插件图标
- 🔗 **仓库链接** - 在设置面板新增插件仓库连接
- 🔧 **生命周期优化** - 优化插件卸载、安装和删除逻辑

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

## 📮 联系方式

- **作者**: lhstack
- **邮箱**: lhstack@foxmail.com
- **GitHub**: [https://github.com/lhstack](https://github.com/lhstack)

---

<p align="center">
  如果这个项目对你有帮助，请给一个 ⭐️ Star！
</p>
