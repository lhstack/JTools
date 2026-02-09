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
| 🧰 **函数调用** | 聚合插件与系统工具并增加唯一标识，避免名称冲突 |
| 📦 **插件管理** | 支持本地/URL/批量安装与卸载的工具调用 |
| 🧪 **开发测试** | 开发面板可暴露当前插件实例的工具调用用于调试 |
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


### v1.1.2.9 (当前版本)
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
