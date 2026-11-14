# Debugger 完整使用手册

本手册配套 JTools「插件沙箱调试器」`0.1.0`，适用于 Java/Kotlin Swing、IDEA API 扩展与 JS/JCEF 扩展。它提供独立 IDEA 进程中的临时运行、生命周期事件、Swing 语义交互、JVM 断点、CDP 调试与截图，不负责替代目标工程的编译打包。

## 1. 环境原则：使用当前启动的宿主 IDEA

**使用宿主机上目前正在运行、承载当前 JTools 会话的 IDEA 安装来启动沙箱，并使用该 IDEA 中用户已经安装的 JTools 插件目录。** 当前能够通过 JTools 使用此技能并对话，默认说明宿主已安装 JTools，不要求用户自行另下 JTools，也不自动联网下载。不是下载另一套 IDEA，不是默认使用 Gradle 缓存中的 IDEA，也不是把被测插件安装进当前日常 IDEA。

架构如下：

```text
当前正在运行的宿主 IDEA + JTools + debugger 控制端
    ├─ 项目/会话绑定的 sandbox_debug 工具
    ├─ 进程外 JDI/JDWP 控制 → 沙箱 JVM
    └─ 回环 RPC → 新启动的独立 IDEA + JTools 沙箱运行端
                                  ├─ 临时加载被测 JAR/ZIP
                                  ├─ Swing 面板 / JCEF 页面
                                  └─ 回环 CDP → 当前面板唯一绑定的浏览器 target
```

共享的是 **IDEA 安装程序及其平台版本**，不是日常实例的用户数据。沙箱新建空测试项目、system、log、plugins、JTools 数据、临时目录和专用 HOME；默认可复用调试器专用 config/home，不复制个人 IDE 配置或凭据。桥接返回的项目路径因此应是沙箱测试项目，而非源码工作区。

### 如何确认宿主路径

1. 先由当前 IDE 的“关于”、实际安装位置或用户提供的信息确认发行版、版本、架构与安装根目录；有多个 IDEA 时不能凭目录名选一个。
2. `launcher` 选择该安装内的实际可执行文件。macOS 常见结构是 `<当前 IDEA.app>/Contents/MacOS/idea`；Linux 常见为 `<当前 IDEA>/bin/idea.sh`；Windows 选择该安装 `bin` 下的对应可执行程序。这些只是结构示例，不是本机已确认路径。不要传 `.app` 目录、`open -a` 命令或任意包装命令。
3. `vm_options` 选择同一安装中核实过的基础 VM 配置，或用户明确准备的干净基础配置。不要直接复制日常 IDE 的自定义 agent、路径与调试参数。
4. `jtools_distribution` 使用当前 IDEA 中用户已经安装的 JTools 插件根目录，目录内必须有 `lib/`。参数名称虽为 distribution，**不意味着需要下载发行包**；沙箱会复制这份现有安装。通过当前 IDE 的插件信息及已核实的插件安装位置确认目录，不凭历史版本目录或另一套 IDEA 的安装猜测。不是 JTools ZIP 路径、仓库根、源码目录或某个单独 JAR。若目录无法确认，只请用户提供现有安装位置，不让用户重新下载。
5. 在工具调用前列明这几个路径及来源，请用户补齐无法确认的路径。不要通过全盘扫描、端口扫描或猜测补齐。

**平台基线不等于运行环境选择**：debugger 构建默认平台基线为 IDEA 2022.3、Java 17 字节码；不表示调试时要另装 2022.3。实际能否运行取决于当前 IDEA、JBR、JTools 沙箱接口和被测插件的兼容性，不能据此声称支持所有 JetBrains 版本。当前手册中的真实 JCEF 验收不代表所有操作系统都已验收。

## 2. 安装包、安装和版本检查

相对此 Skill 的安装包：

- `assets/debugger/plugin-debugger-0.1.0.jar`
- 文件大小：`103957` 字节。
- SHA-256：`306cf35dcb70aa39b36fec559fb1509c71612cfba935b88258d4ce15970697fe`
- 同一 JAR 同时包含 `META-INF/ToolsPlugin.txt` 控制端入口、`META-INF/JToolsSandbox.txt` 沙箱运行端入口，以及新版 `CdpOperations`。

### 安装顺序

1. 在当前宿主 JTools 的插件管理中，使用已有的本地安装能力安装上述 JAR。它是 **JTools 扩展插件**，不是独立 JetBrains 插件发行 ZIP，不要走 IDEA 插件市场安装流程。
2. 已有旧版本时，先停止旧调试工作并确认是否保留沙箱；不要在仍需查询操作结果时卸载控制端。卸载控制端会清理它管理的沙箱。
3. 刷新当前会话可用工具，寻找当前实际暴露的 `sandbox_debug`。工具前缀由安装实例生成，可能形如 `plugin_<动态标识>_sandbox_debug`，不要照抄旧会话名称。
4. 调用 `help` 检查契约，必须包含 `js.cdp(...,async=false)`、`async=true` 和 `js.operation`。版本号同为 `0.1.0` 不能证明是同一构建，应结合文件校验与实际帮助判断。
5. 若仍显示旧帮助，重新加载插件/会话；必要时重新启动当前宿主 IDEA，再检查。不应因旧帮助而改用同步长等待。用新 JAR **新启动**沙箱，避免旧运行端继续驻留。

导入 Skill 只安装知识和附件，不会自动安装或授权 debugger。若调试工具不存在，应明确让用户安装随附 debugger，不要假造调用，也不要因此要求另下 JTools。当前对话默认已有 JTools，但不保证其版本包含 `SandboxHost` / `SandboxRuntime` 与启动支持；若现有安装缺少这些能力，显式报告具体兼容性问题，等待用户确认升级或更换方案，不自动下载、升级、替换 JTools，也不静默改用其他发行目录。

### 安装包更新

此仓库维护者更新 debugger 时，在仓库根执行 `./gradlew :plugin-debugger:jar`，将实际产物复制到此 Skill 的 `assets/debugger/`，同步文件大小、SHA-256 和命令说明。该命令只构建 debugger，不等于构建/验证整个 JTools 发行包。构建需要与目标平台兼容的 JDK/Gradle 环境；不要为了导入技能自动安装依赖或执行完整构建。

## 3. 工具调用格式

以下 JSON 都是传给 **当前实际 `sandbox_debug` 工具** 的参数，不是 shell 命令，不是另一个名为 `jtools.plugins` 的接口。`<...>` 都须替换为现场值，禁止原样执行。

```json
{"command":"help"}
```

除 `help`、`start` 外，所有命令的 `arguments` 必须有 `sandbox_id`。ID 绑定当前项目和会话，重启控制端后不要假定旧 ID 可用。`panel_id`、`operation_id`、`request_id`、`thread_id` 均从实际返回值获取，不可猜测、混用或跨会话复用。

`timeout_secs` 是 RPC 等待配置，取值 `1..300`，默认 `30`；它 **不会** 改变同步 CDP 内部的 20 秒响应等待，也不是浏览器操作取消开关。

### start 完整示例

```json
{
  "command":"start",
  "arguments":{
    "launcher":"<当前宿主 IDEA 可执行文件绝对路径>",
    "vm_options":"<已核实的干净基础 VM 配置绝对路径>",
    "jtools_distribution":"<当前 IDEA 中已安装的 JTools 插件根目录绝对路径，含 lib>",
    "debugger_jar":"<此 Skill>/assets/debugger/plugin-debugger-0.1.0.jar",
    "sandbox_parent":"<已存在的专用沙箱父目录绝对路径>",
    "fresh":false
  }
}
```

| 参数 | 契约 |
| --- | --- |
| `launcher` | 必填、已存在、可执行的普通文件；使用当前宿主 IDEA 安装 |
| `vm_options` | 必填、已存在、小于 1 MiB 的基础 VM 配置 |
| `jtools_distribution` | 必填、当前 IDEA 已安装的 JTools 插件根目录，包含 `lib/`；无需另下 JTools，复制时拒绝符号链接 |
| `debugger_jar` | 必填、包含沙箱运行端入口；与控制端保持同一构建 |
| `sandbox_parent` | 必填、已存在目录；由用户确认，优先使用较短的专用路径 |
| `fresh` | 可选布尔值，默认 `false`，不是字符串 `"false"` |

基础 VM 配置中的 `-javaagent`、`-agentlib`、`-agentpath`、`-Didea.*`、`-Djtools.*`、`-Duser.home`、`-Djava.io.tmpdir`、`-Dide.browser.jcef.*` 等会触发冲突校验。少量受管理的统计/引导选项由调试器移除后统一生成。遇到冲突应确认干净配置，不能把日常 IDE 个人配置带入沙箱。

### 启动与复用

`start` 返回 `sandbox_id`、`pid`、`root`、`state`、`reused`、`fresh`、`config_path`。`STARTING` 不代表就绪；即使返回 `READY` 也要通过 `ping` 验证运行端。

```json
{"command":"status.process","arguments":{"sandbox_id":"<sid>"}}
{"command":"ping","arguments":{"sandbox_id":"<sid>"}}
{"command":"startup.logs","arguments":{"sandbox_id":"<sid>","offset":0}}
{"command":"startup.ui","arguments":{"sandbox_id":"<sid>"}}
```

- `status.process` 在控制端报告 `alive/pid/endpoint_ready/exit_code/startup_hint`；运行端未就绪时优先使用。
- `ping` 成功且 `ready=true` 才继续 `run`。
- `startup.logs` 分段读取 `process.log`，后续用返回的 `next_offset`，不要反复从头读取；偏移是字节而非行数。
- `startup.ui` 只在 RPC 可用后列出模态对话框标题，依赖 EDT，不负责点击许可或自动接受协议。
- 首次启动可能需要用户手动完成许可等交互；工具不会自动同意协议。
- 默认同项目/会话、同启动参数复用存活沙箱；终止后可复用专用配置。`fresh=true` 创建全新环境，可用于干净验收/并行。即使 `fresh=false`，不同会话也不是随意接管同一存活进程。
- 需要彻底清除插件泄漏的静态状态时，先终止进程再新建沙箱；仅 `stop` 不是 JVM 重启。

## 4. 运行、面板与生命周期

启动前先按工程真实构建配置产生 JAR/ZIP。Java/Kotlin 产物根包含 `META-INF/ToolsPlugin.txt`，JS 产物根包含 `pluginInfo.json`；不要把包含这些文件的外层目录再套一层 ZIP。

```json
{"command":"run","arguments":{"sandbox_id":"<sid>","artifact":"<被测 JAR/ZIP 绝对路径>"}}
{"command":"status","arguments":{"sandbox_id":"<sid>"}}
{"command":"events","arguments":{"sandbox_id":"<sid>","after":0}}
{"command":"panel.open","arguments":{"sandbox_id":"<sid>"}}
{"command":"panel.close","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>"}}
{"command":"stop","arguments":{"sandbox_id":"<sid>"}}
{"command":"terminate","arguments":{"sandbox_id":"<sid>"}}
```

- 每个沙箱一次只运行一个产物，换包先 `stop`。`run` 将产物复制到沙箱后临时加载，不向日常 JTools 正式安装。
- 入口校验、支持性检查、install/openProject/createPanel/showPanel 都可能失败；读取真实异常和 `events`，不要把“入口文件存在”说成运行成功。
- `run/status` 返回 `run_id/state/panels/event_cursor`，`panels` 是 ID 列表。UI 插件通常在运行时自动开面板；非 UI 插件没有面板。`panel.open` 遵循插件多开能力，不支持多开的插件不会强制复制面板。
- `events` 是生命周期/日志/错误事件，与 `jvm.events`、`js.events` 不同。最多保留 2000 条，每次最多 200 条；消费时用实际读到的最大 `seq` 继续，避免跳过未读页。
- `stop` 尝试关闭各面板，调用 closeProject/unInstall 并释放类加载器；清理失败显式报告 `CLEANUP_FAILED`，不能称为清理成功。
- `terminate` 强制终止沙箱及子进程，返回的 `cleanup_callbacks_verified=false` 意味着 **未验证正常生命周期清理回调**，不能用它代替 stop 验收。终止后运行端操作记录不再可查询，应提前收集证据。
- `status/events/panel/stop` 等依赖沙箱 EDT；EDT 在 JVM 断点暂停时可能无法返回。恢复用进程外 `jvm.*`，不要循环向 EDT 排队。

产物安全限制：输入不超过 256 MiB，解压不超过 512 MiB、20000 个条目，路径禁止越界。这是加载边界校验，不是恶意代码防护。

## 5. Swing 界面自动化

先获取最新结构，再从 `ref` 选择目标：

```json
{"command":"swing.snapshot","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>"}}
{"command":"swing.action","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","ref":"<输入框 ref>","action":"input","text":"测试输入"}}
{"command":"swing.action","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","ref":"<按钮 ref>","action":"click"}}
{"command":"swing.operation","arguments":{"sandbox_id":"<sid>","operation_id":"<上一步返回的 operation_id>"}}
{"command":"swing.screenshot","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>"}}
```

`snapshot` 返回 type/name/text/enabled/showing/bounds/selected/children 等树信息，密码框 text 脱敏。最多描述 2000 个控件；每次 snapshot 会替换之前的 ref，**即使快照来自另一个面板也不可再用旧 ref**。控件关闭、替换或重跑后重新获取。

| action | 参数与限制 |
| --- | --- |
| `input` | `text`，文本组件；当前实现要求非空字符串，不能用空字符串清空 |
| `click` | 仅 AbstractButton 语义点击；不是物理鼠标事件 |
| `select` | `index`，从 0 起，支持下拉框/列表/表格/标签/树的对应索引，必须在范围内 |
| `scroll` | `x/y`，组件局部坐标，调用 scrollRectToVisible |

目标必须属于指定面板且可见、可用。自绘组件、拖拽、原生系统窗口、焦点和键盘快捷键不是上述语义操作的已验证能力，不能伪装成物理输入验收。

`click` 验证后异步提交，立即返回 `operation_id/QUEUED`；用 `swing.operation` 查 `QUEUED/RUNNING/COMPLETED/FAILED`，失败含 `error/stack`。查询不等待 EDT，适合点击回调命中 JVM 断点后查看。最多保留 1000 个点击记录，只淘汰已结束记录；全部未结束时拒绝新点击。

**COMPLETED 仅表示按钮回调返回**，若回调启动后台任务，业务仍可能未完成。恢复后通过新快照、日志与业务状态验证。超时或 `RUNNING` 不应重发点击，原操作可能继续执行。

Swing 截图返回 `image_path/width/height/capture_kind=screen_region/occlusion_possible=true`，是屏幕区域捕获，可能受遮挡、窗口位置和 OS 屏幕权限影响。不能单独凭它断言业务正确，也不能把它当作 JCEF 页面内部截图。

## 6. JVM 断点、栈帧、变量与恢复

使用真实编译类名与源码可执行行（从 1 起）。初始化断点应在 `run` 前设置；类未加载时 `bound=false` 是待绑定，不等于断点失效。

```json
{"command":"jvm.breakpoint","arguments":{"sandbox_id":"<sid>","class":"example.MyPlugin","line":42}}
{"command":"jvm.events","arguments":{"sandbox_id":"<sid>","after":0}}
{"command":"jvm.threads","arguments":{"sandbox_id":"<sid>"}}
{"command":"jvm.frames","arguments":{"sandbox_id":"<sid>","thread_id":"<事件返回的 thread_id>"}}
{"command":"jvm.variables","arguments":{"sandbox_id":"<sid>","thread_id":"<tid>","frame":0}}
{"command":"jvm.fields","arguments":{"sandbox_id":"<sid>","thread_id":"<tid>","frame":0,"variable":"<对象局部变量名>"}}
{"command":"jvm.step","arguments":{"sandbox_id":"<sid>","thread_id":"<tid>","depth":"over"}}
{"command":"jvm.resume","arguments":{"sandbox_id":"<sid>"}}
{"command":"jvm.remove","arguments":{"sandbox_id":"<sid>","request_id":"<断点或其他请求 ID>"}}
```

- `jvm.events` 记录 breakpoint.bound、breakpoint.error、断点/单步/异常事件、断连等，Locatable 事件包含线程与位置。最多保留 1000 条、每次最多 200 条；为避免分页跳读，用实际返回事件的最大 `seq` 更新 after，不盲用可能领先于本页的全局 cursor。
- Kotlin 顶层函数、lambda、协程/挂起函数可能对应 `XxxKt` 或生成类，必须依据实际编译与调试信息确定；无可执行位置的行会报错，不保证源码每一行可断。
- `frames/variables/fields` 要求目标线程暂停。frames 最多 100 层，variables/fields 最多 200 项；无局部变量表可能无法读取，不能填充假值。
- 对象返回引用 ID/type，字符串可能截断，`fields` 查看某个对象局部变量的字段；这不是完整对象遍历器，也不支持任意 Java/Kotlin 表达式求值。
- `depth=into/over/out`，单步请求提交后会恢复 VM，需再读事件确认停在哪里。
- `jvm.pause` 手动暂停整个 JVM；`jvm.resume` 恢复。暂停计数需配对，不要重复 pause 后假定一次 resume 能解除所有暂停。
- `jvm.exception` 参数是已加载且唯一的异常类 `class`，以及 `caught`（默认 false）、`uncaught`（默认 true）。异常类未加载不能假装已设置延迟异常断点。
- JVM 控制在宿主进程外运行，沙箱 EDT 暂停时仍可恢复；JS 的 `Debugger.resume` 不能代替 `jvm.resume`，反之亦然。

推荐闭环：先设断点 → run 或异步 click → 从事件确认命中 → 查栈/变量 → 单步或恢复 → 查原 operation → 再验证 UI/业务结果。同步 `run` 若因初始化断点超时，不表示原 run 已取消；先用 JVM 事件定位并恢复，不重发 run。

## 7. JS/JCEF 与 CDP

### 7.1 页面绑定与域启用

先从 run/status 获取 JS 面板 ID，等待页面加载，再发最小 CDP 请求完成绑定。工具用宿主浏览器内唯一挑战绑定 target，不按 URL、标题、target 顺序猜测。

```json
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Runtime.enable","params":{}}}
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Debugger.enable","params":{}}}
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Page.enable","params":{}}}
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Runtime.evaluate","params":{"expression":"({ready:document.readyState,title:document.title,bridge:typeof globalThis.JTools})","returnByValue":true}}}
{"command":"js.events","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","after":0}}
```

允许 CDP 域：Runtime、DOM、DOMSnapshot、Accessibility、Input、Page、Network、Debugger、Log。先按协议启用所需域，不存在通用的 `Input.enable`。具体 method/params 以当前 JCEF 的 CDP 协议为准，不承诺所有 Chromium 版本支持相同方法。`js.events` 返回 events/cursor/latest/oldest，按返回 cursor 继续，单次最多 200 条、最近最多 1000 条。Network 事件 payload 会替换为 redacted，不是完整 HAR 或网络凭据抓取器。

### 7.2 异步契约：断点暂停不能使用同步长等待

`js.cdp` 默认 `async=false`，同步等待 CDP 响应最多 20 秒。**可能触发断点的求值、点击释放、页面操作应显式 `async=true`。** 增大外层 timeout_secs 不解决同步响应等待的问题。

```json
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Runtime.evaluate","async":true,"params":{"expression":"(()=>{debugger;return 42})()","returnByValue":true}}}
{"command":"js.operation","arguments":{"sandbox_id":"<sid>","operation_id":"<上一步返回的 operation_id>"}}
{"command":"js.events","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","after":0}}
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Debugger.resume","params":{}}}
{"command":"js.operation","arguments":{"sandbox_id":"<sid>","operation_id":"<同一个 operation_id>"}}
```

此示例须先成功完成 target 绑定并启用 Debugger。预期观察：提交返回 `operation_id/QUEUED` → Debugger.paused 事件 → 操作仍 RUNNING → resume → COMPLETED 且 result 中求值结果为 42。是否停住以事件为准，不预先宣称一定命中。

| 状态 | 含义 |
| --- | --- |
| QUEUED | 已建立操作记录并返回提交凭据，不等于业务开始/完成 |
| RUNNING | 等待协议响应，断点暂停期间保持此状态是正常情况 |
| COMPLETED | 收到成功 CDP 响应，包含 result；不等于业务正确 |
| FAILED | 发送、连接、协议或释放等失败，保留 error/stack |

异步响应由 WebSocket 回调更新，不为每个操作创建阻塞等待线程；不会仅因断点暂停超过 20 秒失败，但发送阶段仍限时，断连仍会失败。`async=true` 不免除首次 target 绑定：必须在暂停前先成功绑定，不能在浏览器已停住后期望首次调用立即返回。

`js.operation` 只需 sandbox_id/operation_id，不访问 EDT、不重新绑定 target。最多保留 1000 条操作，只淘汰已结束记录；全是待完成记录时显式拒绝提交。查询不是消费/删除，也不自动重发操作。没有取消浏览器操作的公共命令；不要把“停止等待”当成已取消。

通过工具 `panel.close` 或 `stop` 释放连接后，未结束 CDP 操作变 FAILED，仍可在该沙箱运行端存活且记录未被淘汰时查询；常见释放原因是 `java.lang.IllegalStateException: CDP 连接已释放`。`terminate` 后不能再查询。不要把运行端存活时的记录保留承诺外推到 IDE 崩溃或进程退出后。

### 7.3 真实按钮点击与 URL 断点

1. 由 `Debugger.scriptParsed`、实际页面脚本 URL/源码确定脚本和函数行，调用 `Debugger.setBreakpointByUrl`。CDP 的 `lineNumber/columnNumber` **从 0 起**，与 JVM 行号不同。
2. 用 DOM/Runtime 获取真实按钮与输入框信息，不猜固定屏幕坐标。输入可以用 CDP 的 focus/Input.insertText 等；程序赋值时需理解 input/change 事件语义。
3. 点击先发送 mousePressed，再用 **async=true** 发送可能触发处理器的 mouseReleased；坐标取自 DOM 的视口边界框，不能直接使用 OS 屏幕坐标。

```json
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Debugger.setBreakpointByUrl","params":{"url":"<实际脚本 URL>","lineNumber":10}}}
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Input.dispatchMouseEvent","params":{"type":"mousePressed","x":120,"y":80,"button":"left","clickCount":1}}}
{"command":"js.cdp","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>","method":"Input.dispatchMouseEvent","async":true,"params":{"type":"mouseReleased","x":120,"y":80,"button":"left","clickCount":1}}}
```

示例的 URL、行号与坐标必须替换为实际页面值。命中后通过 `js.events` 获取 Debugger.paused，按 callFrameId 使用 `Debugger.evaluateOnCallFrame` 检查局部变量；可用 Debugger.stepOver/stepInto/stepOut 单步，再读暂停事件。恢复后查询 **原 operation_id**，不要重发 release。最后读取页面结果/桥接响应验证业务。断点用返回的 breakpointId 配合 Debugger.removeBreakpoint 清理。

### 7.4 宿主桥接与异常判断

- `typeof globalThis.JTools` 只能证明对象存在，不证明桥接调用成功。按当前插件实际使用的 SDK 接口调用，并检查真实 resolve/reject；不要编造桥接方法名。
- 返回 Promise 的求值按需加 `awaitPromise=true`、`returnByValue=true`，可能断点暂停时仍使用 async。
- `Runtime.evaluate` 的 CDP 响应成功，内部仍可能含 `exceptionDetails`，异步记录也可能是 COMPLETED。必须检查异常字段和业务返回，不可只看状态。
- 项目路径桥接应指向沙箱空测试项目；需要源码数据时应经用户确认复制最小测试夹具，不挂载或复制私人工作区来伪造同环境。

### 7.5 JCEF 截图

```json
{"command":"js.screenshot","arguments":{"sandbox_id":"<sid>","panel_id":"<pid>"}}
```

返回 `image_path/width/height/capture_kind=jcef-cdp`，图片落在沙箱 `evidence/`。这是 Page.captureScreenshot 输出，不是 Swing 的 screen_region。截图是本地文件路径，不是自动上传附件；若环境提供图片读取工具，按返回路径实际查看。建议浏览器恢复且 UI 稳定后再截图，断点暂停中的协议响应不保证立即返回。

## 8. 故障排查速查

| 现象 | 最小排查与处理 |
| --- | --- |
| 找不到 sandbox_debug | 确认安装在当前宿主 JTools、插件加载成功及会话工具授权；导入 Skill 不等于安装插件 |
| help 没有 async/js.operation | 控制端仍是旧构建；核对 SHA、重新加载/重启，再新建使用同一 JAR 的沙箱 |
| 沙箱不存在/不属于当前会话 | 核对当前项目会话与 start 返回 ID；不要复用历史 ID、伪造接管 |
| 启动后未就绪 | status.process → startup.logs；排查实际 launcher/VM/JTools 目录、进程退出码、首次许可弹窗 |
| NoClassDefFoundError/NoSuchMethodError | 核对当前 JTools 沙箱接口、平台版本和产物依赖；不打包平台类或静默绕过 |
| 基础 VM options 冲突 | 使用确认过的干净基础配置；不能删除安全校验或继承日常 agent |
| Unix domain path too long | 结合日志确认影响；必要时经用户确认换较短 sandbox_parent 后新建，不自动扫目录或变更配置 |
| CDP ConnectException | 检查新沙箱生成的 sandbox.vmoptions 和日志；端口应通过 ide.browser.jcef.debug.port 设置，不能以延长请求超时掩盖未监听 |
| target 绑定失败（0 或多个匹配） | 确认正确 JS 面板、页面已加载且未先暂停；不按 URL/标题/列表顺序随意选 target |
| 点击后 20 秒超时 | 若是 CDP 同步等待，下一次独立测试采用 async=true；当前先查事件并恢复，不重发已发送点击 |
| operation 一直 RUNNING | 检查 JS/JVM 暂停及页面业务；用对应恢复命令，记录观察时间；RUNNING 本身不是失败 |
| EDT 命令超时 | 排查 JVM 断点或真实 EDT 阻塞；用 jvm.events/frames/resume，不连续排入 UI 操作 |
| js.operation FAILED，连接已释放 | 若紧接工具 panel.close/stop，这是预期失败收敛；核对真实 error/stack，不当成业务成功 |
| 控件 ref 失效 | 重新 snapshot，使用最新 ref；不猜 ID 或跨面板复用 |
| COMPLETED 但页面不对 | 检查 evaluate.exceptionDetails、Promise、后台业务完成信号和页面结果 |
| Network 信息缺失 | 事件负载默认脱敏，不承诺完整 HAR/凭据；不要关闭脱敏来搜集日常 Token |
| 截图为空/与期望不符 | 区分 screen_region 与 jcef-cdp；检查遮挡、权限、页面加载状态，结合结构/业务结果验收 |

JCEF 参数维护注意：IntelliJ 的 `ide.browser.jcef.extra.args` 按逗号拆分，不能空格拼接多个参数。本包通过 `-Dide.browser.jcef.debug.port=<port>` 设置调试端口，extra.args 仅设置回环监听地址。端口由当前沙箱分配；不要端口扫描、自动换端口重试、连接日常浏览器或重发业务请求来掩盖真实启动错误。

## 9. 验收、证据与结束

### 最小验收矩阵

| 范围 | 至少核对 |
| --- | --- |
| 基础设施 | 使用当前宿主 IDEA 安装，独立 PID/目录，ping.ready=true，未复制日常凭据 |
| Java/Kotlin | 入口与 install/openProject，面板或非 UI 运行；真实交互与结果 |
| JVM 调试 | 断点命中事件、线程/栈/变量、恢复、原操作结束；没有仅凭 bound=true 判定通过 |
| Swing | 最新 snapshot/ref、操作状态、界面业务状态；截图声明遮挡限制 |
| JS/JCEF | 页面加载、bridge 对象、真实桥接响应、DOM 结果、CDP 截图 |
| JS 异步断点 | 提交 ID、paused 事件、暂停超过 20 秒仍可查询、resume 后同一 ID 完成且结果正确 |
| 关闭失败收敛 | 有意保留一个待完成 CDP 操作，通过工具 panel.close/stop 后变 FAILED 且保留 error/stack |
| 生命周期 | 多开按插件契约验证，关闭一个不破坏其他；stop 清理事件，terminate 另行标记强制终止 |

不是所有任务都要执行全部项目，只选择本次改动相关项。故意关闭待完成操作会改变测试状态，应先确认不会丢失仍需取回的业务结果。正常验收不需要反复长暂停。

此前配套构建已完成相关 18 项测试与一次真实 JS 沙箱验收：CDP 连通、输入/异步点击、断点长暂停与恢复、页面结果、项目路径桥接、CDP 截图、关闭后 FAILED。**这是该构建的已有证据，不是用户当前机器或新插件自动通过的证明**；本手册封装并未再次运行这些测试。

结束前：

1. 记录必要的事件 seq、原 operation_id、结果、原始错误/堆栈和实际截图路径；只收集与当前问题相关的最小信息。
2. 清理本次设置的断点并恢复暂停，执行 `stop`；检查清理错误。
3. 询问是否保留沙箱。保留时说明进程仍运行；不保留时执行 `terminate`，不要把强制结束记为正常回调已验证。
4. 未经确认不删除专用 profile、日志、证据目录，也不迁移或重建用户数据。
5. 汇报区分：已验证、未验证、环境限制、遗留问题。编译成功、CDP COMPLETED、UI 业务完成是三个不同结论。

## 10. 安全与能力边界

- 独立进程/目录 **不是 OS 级沙箱**。被测插件仍以用户权限访问本机文件与网络，只运行用户信任的产物，不使用日常密钥。
- 调试端口为回环端口，但不应对外转发。沙箱 token 属于凭据，不粘贴到聊天、文档或技能附件；不要公开整个沙箱目录。
- 截图、DOM、变量和错误堆栈也可能含敏感内容；使用脱敏夹具，只读取必要片段。
- 不自动接受许可、不输入账号、不从个人配置复制认证信息。
- 没有通用任意 JVM 表达式求值、全系统 UI 自动化、浏览器操作取消、崩溃后操作持久化恢复等承诺。
- 需要修改被测插件的并发、生命周期、数据或公共契约时，先定位根因并按项目规则取得方案确认；调试工具不能代替修改授权。
