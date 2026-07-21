# JTools

JTools 是一个面向 JetBrains IDE 的插件管理与开发工具集，支持插件生命周期管理、热插拔、开发调试和日志输出。

## v1.1.0.3

本版本更新：

- 对话框上方增加“发送”按钮，支持点击发送消息，并保留 Cmd/Ctrl+Enter 快捷发送。
- Agent 技能提示词注入技能描述，帮助模型理解技能用途。
- 修复 SubAgent 工具调用详情无法加载入参和出参的问题。

## v1.1.0.2

本版本新增内置智能体能力：

- 支持智能体会话管理、流式输出、推理内容、附件和工具调用日志。
- 支持供应商、模型、提示词、智能体、Skills、MCP 服务、全局设置和模型日志管理。
- 支持 OpenAI、OpenAI 兼容接口和 Anthropic 模型供应商。
- 提供 Bash、Web Fetch、Skills、MCP、IDE 项目操作和 JTools CLI 等内置工具。
- SDK 新增 Function Calling 扩展，已安装插件和开发中的插件均可向智能体暴露工具。
- 使用 SQLite 在本地持久化智能体配置、会话和运行数据。
- 适配 IntelliJ IDEA 2022.3 下 JCEF 管理弹窗的调度、页面加载和边框样式。
