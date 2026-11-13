---
name: jtools-plugin-development
description: 开发、修改、调试和打包 JTools 扩展插件。用于用户要求为 JTools 创建 Swing、IntelliJ IDEA API 或 JS/JCEF 插件，选择 Maven/Gradle 模板，配置 SDK 和第三方依赖，生成可安装 JAR/ZIP，或排查插件入口、生命周期、类加载、JS 桥接与打包失败时。
---

# JTools 插件开发

用此 Skill 提供的工程模板和通用文件、终端工具完成开发。不要调用 `jtools.plugins`：宿主不提供专用开发指令，也不自动安装此 Skill。

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
7. 按 [调试与故障定位](references/debugging.md) 用目标 JTools/IDE 现场验收，未执行 IDE 验收必须明确说明。

## 安装此 Skill

此目录独立于插件发行包。用户可在 JTools 的 Skills 管理中导入整个 `jtools-plugin-development` 目录，选择项目或全局范围；也可手动复制完整目录到目标项目 `.jtools/skills/jtools-plugin-development` 或用户目录 `.jtools/skills/jtools-plugin-development`。保留 references、assets、agents 子目录；已有同名目录先比较再决定是否覆盖。不要把此仓库目录当成已经安装。
