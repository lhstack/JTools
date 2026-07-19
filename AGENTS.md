# AGENTS.md

## 项目概述
- 本仓库是一个基于 Gradle 的 IntelliJ IDEA 插件项目，根项目名为 `tools`。
- 项目由插件主体、插件 SDK 和示例插件三个模块组成：`plugin`、`sdk`、`plugin-example`。
- 当前仓库未提供受版本控制的 README，具体业务能力应以模块源码和 `plugin/src/main/resources/META-INF/plugin.xml` 为准。

## 工程环境与主要工具
- 构建工具：Gradle Wrapper，构建脚本使用 Kotlin DSL。
- 主要语言：Kotlin 和 Java。
- IntelliJ 平台构建插件：`org.jetbrains.intellij` 1.17.2。
- 当前分支构建配置使用 Kotlin 1.9.22；插件主体及示例模块目标 JVM 17，SDK 模块目标 Java 8/JVM 1.8。
- 插件主体当前基于 IntelliJ IDEA Community 2022.3（build 223），并声明兼容至 251.*。
- 依赖仓库包括 Maven Local、阿里云 Maven 公共仓库和 Maven Central。

## 目录与模块结构
- `plugin/`：IDE 插件主体，Kotlin 源码位于 `src/main/kotlin`，资源与插件描述文件位于 `src/main/resources`。
- `sdk/`：提供给扩展插件使用的 Java API，核心接口位于 `com.lhstack.tools.plugins` 包。
- `plugin-example/`：SDK 的示例使用模块。
- `gradle/`、`gradlew`、`gradlew.bat`：Gradle Wrapper。
- `docs/`、`META-INF/`：当前存在于工作区，但是否属于稳定的项目输入需按 Git 跟踪状态逐项确认。
- 构建产物、IDE 元数据、依赖目录和缓存目录不得作为源码分析或迁移输入。

## 分层架构与依赖方向
- `plugin` 依赖 `sdk`；`plugin-example` 依赖 `sdk`。
- `sdk` 定义扩展契约，插件主体负责 IntelliJ 平台集成、生命周期、UI、插件管理和契约实现。
- IntelliJ 注册入口及扩展点以 `plugin.xml` 为边界；迁移入口类时必须同步核对对应注册项和资源。
- 不应让 `sdk` 反向依赖 `plugin`，也不应把 IntelliJ 平台实现细节下沉到 SDK 契约层。

## 构建、测试和验证方式
- 常规构建入口：`./gradlew build`。
- 模块级验证优先使用对应任务，例如 `./gradlew :plugin:compileKotlin`、`./gradlew :sdk:compileJava`。
- IntelliJ 插件验证还应核对 `patchPluginXml` 的 since/until build 范围和 `plugin.xml` 注册项。
- 仓库当前没有受版本控制的统一测试规范；新增或发现的本地测试必须先确认其 Git 状态，禁止覆盖未提交文件。
- 未经任务要求，不自动执行发布、签名、ProGuard、依赖安装、IDE 启动或未知脚本。

## 项目编码约定
- 保持现有包前缀 `com.lhstack` 和模块既有语言边界。
- Kotlin/Java 源码使用 UTF-8。
- 修改应保持职责内聚：IntelliJ 入口、UI、监听器、状态组件、插件管理和 SDK 契约分别放在对应包或模块。
- 跨分支迁移不能只复制显式包含 `agent` 名称的文件；应通过引用关系确认构建依赖、注册项、资源、状态模型和前端资产等必要闭包。
- 避免把机器专属路径、凭据或本地缓存写入新配置。

## 错误处理约定
- 在 IntelliJ/插件 API、文件、网络和进程等系统边界显式校验输入并暴露失败。
- 不以默认值、吞异常或伪造成功掩盖缺失依赖和版本不兼容。
- 迁移导致的 API 不兼容应在实际根因层修复；不得在调用层增加无契约依据的兼容分支。

## 版本控制信息
- 仓库使用 Git，远端名为 `origin`。
- 分支之间可能长期分叉；跨分支迁移前必须基于 merge-base、提交列表、路径差异和依赖引用评估，不能默认整分支合并安全。
- 执行切换分支、应用补丁、cherry-pick 或重置前必须检查工作区，保留用户已有的已跟踪和未跟踪改动。
- 本文件初始化时，当前分支存在未跟踪测试目录；后续操作不得覆盖或清理这类用户现场。

## 有证据支持的用户编码习惯
- Git 配置身份与近期主要提交作者一致，可确认当前分支与目标分支的主要演进由同一作者维护。
- 现有项目采用按职责命名的类和包（如 `actions`、`components`、`listener`、`plugins`），迁移和新增代码应延续这种组织方式。
- 提交标题普遍简短，部分使用 `fix:` 前缀；更细粒度的提交规范未确认。

## 当前无法确认的事项
- 未确认统一的格式化、静态检查和覆盖率工具。
- 未确认 CI/CD 流程和发布验收清单。
- 未确认 `docs/`、根 `META-INF/` 及本地 Agent 相关未跟踪内容的来源和预期用途。
- 未确认所有支持的 IntelliJ 产品与操作系统组合；应以目标分支构建配置和实际验收要求为准。
