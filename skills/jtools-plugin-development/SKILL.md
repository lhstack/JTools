---
name: jtools-plugin-development
description: 开发、修改、调试和打包 JTools 扩展插件。用于用户要求为 JTools 创建 Swing、IntelliJ IDEA API 或 JS/JCEF 插件，选择 Maven/Gradle 模板，配置 SDK 和第三方依赖，生成可安装 JAR/ZIP，使用独立 JetBrains IDE 沙箱进行 Swing 自动化、JVM 断点、JCEF/CDP 与 JS 异步调试，或排查插件入口、生命周期、类加载、JS 桥接与打包失败时。
---

# JTools 插件开发

用此 Skill 提供的工程模板、通用文件与终端工具完成开发；安装随附 debugger 后，可通过 JTools 动态暴露的 `sandbox_debug`，或外部客户端的 stdio/SSE/Streamable HTTP MCP 执行独立沙箱调试。同一宿主应用中的项目共享唯一 MCP 服务，面板状态与配置通过应用消息总线同步。不要调用 `jtools.plugins`，也不要假定导入此 Skill 就已安装 debugger。

## 开始前

1. 确认目标项目并读取适用的 AGENTS.md。已有工程沿用其构建配置。
2. 新建工程且用户未指定位置时，确认“当前项目中新建子目录（名称）”或“直接在项目根初始化”。不要自行选择，也不要覆盖已有工程文件。
3. 确认插件名称、入口包名、功能和验收标准；确认是否使用 IDEA API、第三方依赖或联网能力。
4. 按下表只读取所需手册，复制完整模板到选定目录，修改名称、包名和 `META-INF/ToolsPlugin.txt`。

| 需求 | 模板（相对此 Skill） | 手册 |
| --- | --- | --- |
| 纯 Java/Swing，Maven | `assets/templates/maven/` | [Maven](references/maven.md) |
| 纯 Java/Swing，Gradle | `assets/templates/gradle/` | [Gradle](references/gradle.md) |
| 直接操作 Project、PSI、VFS、Editor 等平台 API | `assets/templates/gradle-idea/` | [IDEA Gradle](references/gradle-idea.md) |
| HTML/JS 页面，JCEF | `assets/templates/js/` | [JS/JCEF](references/js.md) |

只需文件选择、通知、IDE 图标/工具栏、树搜索等宿主封装能力时，先读 [Helper 使用手册](references/helper.md)，保留 Maven/Swing Gradle 模板；使用 Helper 不等于必须引入 IDEA 构建依赖。需要直接平台 API 时再选 gradle-idea。

## 实施流程

1. 先读 [SDK 与依赖](references/dependencies.md)，Gradle 使用 Shadow、Maven 使用 Shade 打包非宿主自带的依赖；核对 SDK 来源和实际文件。不编造远端 SDK 坐标。
2. Java 先读 [入口与生命周期](references/lifecycle.md)。默认 Java 11 字节码；IDEA 示例用 JDK 17 编译、2022.3 平台基线；宿主 JTools 自身支持版本与示例基线分开。
3. 涉及多项目或 `supportMultiOpens` 时读 [项目缓存与多开](references/state-and-multi-open.md)；涉及 AI 函数或工具栏时读 [函数与工具栏](references/functions-and-actions.md)。区分项目服务和面板实例，多开每次创建新组件，关闭一个面板不清理其他视图的项目状态。可按需采用 [完整多开示例](assets/examples/multi-open/example/MultiOpenPlugin.java)，复制方式见缓存手册。
4. 在模板上实现业务。Swing UI 在 EDT，耗时操作在后台；IDE 写操作使用 write action。及时释放线程、监听器和浏览器。
5. 执行所选模板的实际构建命令；失败显示原始错误。没有工具链时报告缺少项，禁止伪造构建成功或自动安装未知程序。
6. 按 [打包与验证](references/packaging.md) 检查入口、资源、第三方依赖、JAR/ZIP 根目录与图标。交付实际文件和校验结果。
7. 按 [调试与故障定位](references/debugging.md) 排查问题；隔离运行、界面自动化或断点先读 [Debugger 完整使用手册](references/debugger.md)。先自动发现本机兼容的 JetBrains IDE 及匹配的 JTools 安装，优先当前宿主，多个候选请用户选择；定向发现未找到时才询问自定义位置，未安装则提供 Marketplace/Releases 下载入口。不限定 IntelliJ IDEA，也不要求使用 JTools AI。开发和编译可在其他工具完成，运行宿主仍需 JTools 与随附 debugger。

## Debugger 调试技能

- **安装包**：[plugin-debugger-0.2.0-mcp.jar](assets/debugger/plugin-debugger-0.2.0-mcp.jar)。这是 JTools 扩展 JAR，通过当前宿主的 JTools 插件管理安装，不是通过 JetBrains 插件市场安装。宿主 JTools 还必须支持沙箱运行端，只有此 JAR 不够。
- **手册**：[Debugger 完整使用手册](references/debugger.md)，包含安装与版本检查、宿主路径确认、启动参数、生命周期、Swing 自动化、JVM 断点、JS/CDP 异步断点、宿主桥接、截图、排障与验收清单。
- **环境边界**：运行宿主是兼容的 JetBrains IDE，不限定 IDEA；先自动定位当前宿主及匹配的 JTools，再定向扫描已安装产品。找不到才询问用户位置或提供 JTools 下载入口，多个候选不能猜。外部开发工具可经 MCP 调用，不要求 JTools AI。沙箱使用独立进程和专用数据目录，不复制个人配置或密钥，不将被测业务插件安装到日常 IDE；不是 OS 安全隔离。
- **执行顺序**：通过 JTools 或 MCP 发现实际 `sandbox_debug` 工具 → `help` → `discover` 自动发现并校验宿主 → `start` → `ping` → 必要时先设初始化断点 → `run` → 从返回值取 `panel_id` → 交互与断点验收 → `stop` → 按用户意愿 `terminate`。工具前缀动态生成，禁止硬编码历史工具名或 ID。
- **暂停时的操作**：Swing `click` 返回 `operation_id`；JS 中可能命中断点的 CDP 请求使用 `async=true`，用 `swing.operation` / `js.operation` 查询，恢复后再核对业务结果。禁止因等待超时重发点击或用延长超时掩盖问题。
- **交付标准**：区分构建成功、协议成功和业务成功；保留真实 error/stack，说明实际验证与未验证项。安装包的校验值与适用限制见完整手册。

## 安装此 Skill

此目录独立于插件发行包。用户可在 JTools 的 Skills 管理中导入整个 `jtools-plugin-development` 目录，选择项目或全局范围；也可手动复制完整目录到目标项目 `.jtools/skills/jtools-plugin-development` 或用户目录 `.jtools/skills/jtools-plugin-development`。保留 references、assets、agents 子目录；已有同名目录先比较再决定是否覆盖。不要把此仓库目录当成已经安装。
