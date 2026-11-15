# Debugger 完整使用手册

本手册配套 JTools「插件沙箱调试器」`0.2.0`，适用于 Java/Kotlin Swing、IDEA API 扩展与 JS/JCEF 扩展。它提供独立 JetBrains IDE 进程中的临时运行、生命周期事件、Swing 语义交互、JVM 断点、CDP 调试与截图，不负责替代目标工程的编译打包。

## 1. 宿主发现：JetBrains IDE 全家桶

开发、编译和发起调试可以使用任意编辑器或 AI 客户端，不要求使用 JTools 的 AI 会话。运行宿主仍需安装 JTools 主插件及随附 debugger；适用于兼容的 IntelliJ IDEA、PyCharm、WebStorm、GoLand、CLion、Rider、RubyMine、PhpStorm、DataGrip 等 JetBrains 产品，不限定 IDEA。具体版本、JBR、JCEF 和被测插件兼容性必须核对，不把一个产品的验收结果推广到全部产品。

### 自动发现顺序

1. 有 debugger 工具时先调用 `discover`。服务通过当前宿主平台 API 获取实际 IDE 和插件目录，按 `META-INF/plugin.xml` 中的插件 ID `com.lhstack.tools` 校验安装；优先当前宿主。
2. 尚无工具时，用终端检查正在运行的 JetBrains 进程及其产品元数据 `product-info.json`，再定向检查系统安装目录、Toolbox 安装目录和该产品对应的用户插件目录。macOS 常见 `/Applications`、`~/Applications`、`~/Library/Application Support/JetBrains/<产品选择器>/plugins`；Linux 常见 `/opt`、`~/.local/share/JetBrains/<产品选择器>`；Windows 常见 Program Files、LocalAppData 的 Toolbox 和 AppData 的 JetBrains 插件目录。不要扫描整个磁盘或假定目录名一定是 idea/jtools。
3. 用实际产品信息中的 launcherPath、vmOptionsFilePath 解析启动文件；JTools 使用匹配宿主的、含 lib/ 的已安装插件根目录。多个候选时优先当前宿主；仍无法唯一确定则向用户展示候选选择，不能任选另一套 IDE。
4. 定向发现未找到时，才询问用户 JetBrains IDE/JTools 的自定义安装位置。如果未安装 JTools，说明可在该 JetBrains IDE 的 Plugins 中搜索 **JTools**，或从 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.lhstack.tools) / [GitHub Releases](https://github.com/lhstack/JTools/releases) 下载安装。没有 JetBrains IDE 时使用对应产品的 [JetBrains 下载页面](https://www.jetbrains.com/products/)。不要提前要求下载，也不自动替用户安装或接受许可。
5. `start` 的路径参数可省略，由服务按相同规则发现；发现结果不唯一时会显式报错并提示选择。显式提供的路径优先，必须验证实际文件。默认 debugger_jar 使用已加载控制端的完整包，sandbox_parent 使用宿主 system 目录下的专用沙箱位置；可显式覆盖。

沙箱复制已有 JTools 安装和 debugger，在独立 JetBrains IDE 进程运行临时插件；新建空项目和独立 system/log/plugins/JTools 数据。默认只复用调试器专用 config/home，不复制个人配置、项目源码或凭据。共享 IDE 安装程序，不共享日常用户数据。构建默认平台基线不决定实际宿主产品。

## 2. 安装包与三种接入

随附完整包：`assets/debugger/plugin-debugger-0.2.0-mcp.jar`。同一包支持 JTools 扩展安装、沙箱运行端和可执行 stdio 桥接，包含隔离后的 MCP/HTTP 运行依赖，不使用 plain JAR。

- 完整包大小：`11297963` 字节。
- SHA-256：`c906c7d519857a5c72291612abded5a855c5df520ec157f7914aec5ad1d8c525`。
- 校验文件：`assets/debugger/plugin-debugger-0.2.0-mcp.jar.sha256`。

### 安装及更新

1. 自动发现 JTools 后，在该宿主的 **JTools 插件管理**中安装随附完整 JAR。这是 JTools 扩展，不是 JetBrains 插件市场直接安装的发行 ZIP。JTools 主插件才从 Marketplace 安装。
2. 更新已有 debugger 前结束需要保留证据的调试工作；卸载控制端会终止它管理的沙箱。JTools 安装会复制扩展 JAR，替换 Skill 附件或 start 的 debugger_jar 不会更新已加载控制端；应卸载旧扩展，再安装新版。
3. `help` 必须包含 `discover`、`methods`、`invoke`、异步 `js.cdp` 和 `js.operation`。安装后可以使用 JTools 动态暴露的 sandbox_debug，也可使用外部 MCP 客户端。

### 外部 MCP（无需 JTools AI 会话）

在 debugger 面板设置端口（0 自动分配），点击“启动”。配置分为 Streamable HTTP、SSE、stdio 三个标签；选择协议后点击“复制配置”，只复制当前标签的完整 mcpServers JSON，可直接粘贴，不含额外协议名称或说明字段。服务仅监听 127.0.0.1，首次使用默认不启动。启动成功后将启用状态、实际端口、全部客户端令牌及身份保存到 `~/.jtools/plugin-debugger/mcp.json`；首次使用 0 分配的实际端口也会固定保存。IDE 退出只释放监听，重启加载 debugger 时自动使用原端口和令牌恢复，无需打开面板或重配客户端。手动停止或卸载 debugger 关闭自动启动。端口被占用或配置读取失败时，面板显示真实恢复错误，不自动换端口。同一 JetBrains IDE 应用内所有项目、窗口和会话共享唯一 MCP 服务；打开面板先读取运行状态与最新配置，启停及新客户端配置通过应用级 MessageBus 广播到全部面板。已启动时再次启动回显现有服务，已停止时再次停止保持停止状态。

- **Streamable HTTP**：连接面板显示的 `/mcp`，Authorization 为 `Bearer <令牌>`。
- **SSE**：连接同一端口 `/sse`，请求均需相同 Authorization；消息端点由协议返回。SSE 为明确支持的旧版传输，推荐新客户端使用 Streamable HTTP。
- **stdio**：面板生成完整的 command/args/env 配置，自动填入 Java 可执行文件和已安装完整 JAR 的绝对路径、实际端口及 `JTOOLS_DEBUGGER_TOKEN`，无需手动拼接。在 MCP 客户端中原样使用 command/args/env；不要将整条 shell 命令填入 command。使用 Java 17+；stdout 仅有 MCP 消息，日志写 stderr。stdio 子进程连接宿主共享服务，不启动另一份控制器。

终端命令包含令牌设置和完整 Java 调用，两者缺一不可。结构如下（示例中的占位值必须替换，实际操作优先使用面板对应协议标签生成的 command/args/env）：

```bash
JTOOLS_DEBUGGER_TOKEN='<面板令牌>' '/绝对路径/JBR/bin/java' '-jar' '/绝对路径/plugin-debugger-0.2.0-mcp.jar' '--url' 'http://127.0.0.1:<实际端口>/mcp'
```

```powershell
$env:JTOOLS_DEBUGGER_TOKEN='<面板令牌>'; & 'C:\绝对路径\JBR\bin\java.exe' '-jar' 'C:\绝对路径\plugin-debugger-0.2.0-mcp.jar' '--url' 'http://127.0.0.1:<实际端口>/mcp'
```

在 MCP 客户端配置中直接使用 command/args/env，不能把上述整条 shell 命令填进 command。桥接通过 stdin/stdout 运行，直接在终端启动会等待 MCP 消息，没有欢迎输出属于正常行为。

三种接入暴露同一 `sandbox_debug`、Schema 和 help。每次“新客户端配置”生成独立身份，最新配置同步回显到全部面板，之前发出的令牌仍有效；同一令牌重连恢复原沙箱归属，不用 MCP 会话 ID 作为业务 owner。独立用户不要共享令牌。JTools 会话与 MCP 身份隔离。手动停止服务保留沙箱与持久化的令牌；使用已保存端口重新启动可直接复用客户端配置。关闭面板或项目不会停止共享服务，也不会终止 MCP 客户端沙箱；关闭项目仅清理该项目 JTools AI 会话的沙箱。卸载 debugger 或退出 IDE 会关闭共享服务和全部沙箱；IDE 重启后恢复服务端口与客户端身份，旧沙箱进程和对象引用不会恢复，须重新 start/run。

MCP 工具结果包含文本及截图 image 内容；错误返回 isError=true 和真实堆栈。连接中断、HTTP/MCP 超时、取消通知都不证明目标动作已停止，禁止自动重发。被测插件仅临时装载，不自动安装到日常 IDE。

导入 Skill 不会自动安装 debugger；没有工具且没有 MCP 服务时，先按上述发现和安装流程操作，不编造工具。维护者执行 `./gradlew :plugin-debugger:shadowJar` 构建完整包并更新附件，不能使用 `:plugin-debugger:jar` 的 plain 产物代替。

## 3. 工具调用格式

以下 JSON 都是传给 **当前实际 `sandbox_debug` 工具（JTools 或 MCP）** 的参数，不是 shell 命令，不是另一个名为 `jtools.plugins` 的接口。`<...>` 都须替换为现场值，禁止原样执行。

```json
{"command":"help"}
```

除 `help`、`discover`、`start` 外，所有命令的 `arguments` 必须有 `sandbox_id`。ID 绑定当前项目与调用身份（JTools 会话或 MCP 客户端令牌），重启控制端后不要假定旧 ID 可用。`panel_id`、`operation_id`、`request_id`、`thread_id` 均从实际返回值获取，不可猜测、混用或跨会话复用。

`timeout_secs` 是 RPC 等待配置，取值 `1..300`，默认 `30`；它 **不会** 改变同步 CDP 内部的 20 秒响应等待，也不是浏览器操作取消开关。

### start 完整示例

```json
{
  "command":"start",
  "arguments":{
    "launcher":"<当前宿主 JetBrains IDE 可执行文件绝对路径>",
    "vm_options":"<已核实的干净基础 VM 配置绝对路径>",
    "jtools_distribution":"<当前 JetBrains IDE 中已安装的 JTools 插件根目录绝对路径，含 lib>",
    "debugger_jar":"<此 Skill>/assets/debugger/plugin-debugger-0.2.0-mcp.jar",
    "sandbox_parent":"<已存在的专用沙箱父目录绝对路径>",
    "fresh":false
  }
}
```

| 参数 | 契约 |
| --- | --- |
| `launcher` | 可省略自动发现；显式值须为所选 JetBrains IDE 中已存在、可执行的普通文件 |
| `vm_options` | 可省略使用发现的产品基础 VM 文件；显式值须已存在且小于 1 MiB |
| `jtools_distribution` | 可省略自动定位匹配宿主的已安装 JTools；显式值为含 `lib/` 的插件根目录。未找到才询问位置或提供安装入口 |
| `debugger_jar` | 可省略使用已加载控制端完整包；显式值须含运行端入口并与控制端保持同一构建 |
| `sandbox_parent` | 可省略使用宿主 system 中的专用沙箱目录；显式值须为已存在目录，较短路径可避免 Unix socket 长度限制 |
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

### 实例与静态方法反射调用

需同时更新 JTools 主插件和 Skill 随附的 debugger JAR。`run/status` 返回 `plugin_object` 与 `project_object`，包含本次运行的 `object_id`；使用真实插件实例，不重新实例化。`stop` 清空对象引用，重新 `run` 后旧 ID 不可用。

```json
{"command":"methods","arguments":{"sandbox_id":"<sid>","object_id":"<plugin-id>","method":"functionCallings"}}
{"command":"invoke","arguments":{"sandbox_id":"<sid>","object_id":"<plugin-id>","method":"functionCallings","parameter_types":["com.intellij.openapi.project.Project"],"arguments":[{"object_id":"<project-id>"}],"thread":"worker"}}
{"command":"invoke","arguments":{"sandbox_id":"<sid>","object_id":"<返回的List对象ID>","method":"get","parameter_types":["int"],"arguments":[{"value":0}]}}
{"command":"invoke","arguments":{"sandbox_id":"<sid>","object_id":"<返回的FunctionCalling对象ID>","method":"call","parameter_types":["java.lang.String"],"arguments":[{"value":"{}"}]}}
{"command":"invoke","arguments":{"sandbox_id":"<sid>","class_name":"example.MyPlugin","method":"staticMethod","parameter_types":[],"arguments":[]}}
```

- `object_id` 表示实例方法，`class_name` 表示静态方法，必须二选一。`methods` 可选 `method` 过滤，返回继承/非 public 方法的声明类、参数类型、返回类型、static、可见性及 varargs。JVM 模块访问失败会明确报错，不绕过访问限制。
- `invoke` 必须传精确的 `parameter_types` 和对应 `arguments`，无参均传 `[]`。嵌套类用 `$`；数组使用 `int[]` 等；varargs 显式传数组，不猜重载。
- 每个参数只能为 `{"value":简单值或null}` 或 `{"object_id":"..."}`。数组使用 `{"value":[{"value":1},{"value":2}]}`，元素也需封装。拒绝数值溢出、小数转整数、字符串转数字、primitive null 和类型不匹配引用。
- 简单返回值为 `kind=value/value`；其他返回对象引用 `kind=object/object_id/class_name`，可继续 `methods/invoke`。因此可测试 Function Calling 的实际函数对象，无专用函数调用协议。三参/五参 `call` 的会话和模型信息按测试意图显式传入，不自动注入控制端会话。
- `thread` 默认 `worker`，UI 方法明确选 `edt`；使用当前插件 ClassLoader，并恢复线程原有上下文 ClassLoader。目标异常展开后返回真实异常和堆栈。
- 调用沿用沙箱串行操作锁。`timeout_secs` 只限制控制端 HTTP 等待，不取消目标函数、不自动重发；超时不代表未执行。断点暂停或超时后用 `jvm.events/frames/resume`，必要时 terminate，不盲目重试。

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
| 基础设施 | 使用当前宿主 JetBrains IDE 安装，独立 PID/目录，ping.ready=true，未复制日常凭据 |
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
