# 插件沙箱调试器

命令通过 JTools Function Calling 或外部 MCP 的同一 `sandbox_debug` 工具执行。需要同时更新 JTools 主插件和 debugger JAR：主插件沙箱宿主持有被测插件的真实实例及 ClassLoader；debugger 负责会话隔离和 RPC 转发。

## MCP 服务（0.2.0）

安装 `:plugin-debugger:shadowJar` 生成的 `plugin-debugger-0.2.0-mcp.jar`，不要安装 plain JAR。包内隔离了 MCP SDK、Jetty 及 JSON 依赖，不随包携带 JTools SDK 或 IDE 平台类。

应用级唯一 MCP 服务由所有项目、窗口和会话共享。面板打开即回显当前状态与配置，启动、停止及新客户端配置通过 Application MessageBus + Topic 同步全部面板。启动成功后将启用状态、实际端口和全部客户端身份保存到 `~/.jtools/plugin-debugger/mcp.json`，IDE 重启加载 debugger 时自动恢复原端口与令牌，无需重配客户端；手动停止或卸载关闭自动启动。端口占用及配置错误显式显示，不自动换端口。重复启动返回现有服务，重复停止返回停止状态；启停状态在服务层原子更新。面板分为 Streamable HTTP、SSE、stdio 三个标签；选择协议后点击“复制配置”，只复制当前标签的完整 mcpServers JSON，可直接粘贴到客户端。三种方式选一种：

- Streamable HTTP：`http://127.0.0.1:<port>/mcp`，设置 Authorization Bearer。
- 传统 SSE：同端口 `/sse`，同样设置 Authorization Bearer，消息地址由协议返回。
- stdio：面板提供完整 command/args/env，包含 Java/JAR 绝对路径、实际地址及 `JTOOLS_DEBUGGER_TOKEN`；MCP 配置使用分开的 command/args/env，不将整条 shell 命令填入 command。Java 17+，stdout 仅有 MCP 消息。

不要求外部开发工具使用 JTools AI 会话；运行宿主仍需安装 JTools/debugger。支持兼容的 JetBrains IDE 产品，包括 IDEA、PyCharm、WebStorm、GoLand、CLion、Rider 等，不限制产品名。`discover` 优先当前宿主，再按运行进程、系统安装/Toolbox 及用户插件目录发现，按 JTools 插件 ID 验证；`start` 可省略路径。多个候选让用户选择，未找到才询问自定义路径或提供 Marketplace/GitHub 下载入口。不会自动下载安装。

每份新客户端配置是独立调试身份，重连保留原令牌可继续访问原沙箱；MCP 协议会话绑定其创建令牌，不允许跨令牌借用。服务只监听本机并校验 Origin；令牌不写入日志。停止 MCP 服务保留沙箱，按保存端口重启后原令牌仍可用；关闭面板或项目不停止共享服务及 MCP 沙箱；项目关闭仅清理其 JTools AI 沙箱。卸载 debugger 或退出 IDE 统一清理全部运行服务和沙箱；IDE 重启恢复服务与客户端身份，沙箱进程及对象引用不恢复。JTools 会话和外部客户端隔离。

测试：`./gradlew :plugin-debugger:test`；真实 JetBrains IDE 验收须显式提供 `-PdebuggerTestIde=<IDE home>` 和 `-PdebuggerTestDistribution=<JTools root>`。测试夹具目前在本机 IDEA 产品验收，不代表全产品真机验证。

## 对象与方法

`run` 成功及后续 `status` 返回 `plugin_object` 和 `project_object`，包含 `object_id`、`class_name` 和 `kind=object`。对象不跨进程序列化，不重新实例化插件。引用按对象身份复用，只在本次运行有效；`stop` 后清空，再次 `run` 产生新的 ID。

列举实例方法：

```json
{"command":"methods","arguments":{"sandbox_id":"<sid>","object_id":"<plugin_object.object_id>"}}
```

列举静态方法：

```json
{"command":"methods","arguments":{"sandbox_id":"<sid>","class_name":"example.MyPlugin","method":"staticMethod"}}
```

`object_id` 和 `class_name` 二选一。可选 `method` 按名称过滤。返回声明类、参数类型、返回类型、可见性、static 和 varargs，包含继承及非 public 方法；实际访问受 JVM 模块规则约束。

## 精确调用

```json
{"command":"invoke","arguments":{"sandbox_id":"<sid>","object_id":"<plugin-id>","method":"functionCallings","parameter_types":["com.intellij.openapi.project.Project"],"arguments":[{"object_id":"<project-id>"}],"thread":"worker"}}
```

- `parameter_types` 必须精确匹配声明参数；无参传 `[]`，`arguments` 也传 `[]`。嵌套类使用 `$`，数组使用 `int[]`、`java.lang.String[]`；不做重载猜测。
- 参数只能是 `{"value":简单值或null}` 或 `{"object_id":"..."}`。数值按声明类型校验，拒绝整数溢出、小数转整数、字符串转数字和 primitive null。对象引用必须满足声明类型。
- 数组参数使用 `{"value":[{"value":1},{"value":2}]}`，元素同样使用 value/object_id 封装；varargs 显式传数组，不自动展开。
- 静态调用用 `class_name` 替代 `object_id`。使用运行中插件的 ClassLoader，无需新建实例。
- `thread` 默认 `worker`，UI 调用指定 `edt`。调用期间线程上下文 ClassLoader 为插件 ClassLoader，结束后恢复。
- null、字符串、字符、布尔值、数字返回 `{"kind":"value","value":...}`；其他结果返回对象引用，可继续调用，不自动序列化复杂对象。
- 目标异常展开 InvocationTargetException 后按现有 RPC 失败路径报告真实异常与堆栈。

测试 Function Calling 的路径：对 plugin_object 调用 `functionCallings(...)` 得到 List 引用，对该引用调用 `get(int)` 得到 FunctionCalling 引用，再调用 `name()`、`parameters()` 或 `call(...)`。三参、五参 call 必须显式提供实际测试使用的会话和模型信息，不自动注入控制端上下文。JDK 非公开集合实现通过其公开接口执行方法。

## 生命周期、超时与断点

反射命令与 run/stop 共用已有串行操作锁；前一次调用尚未返回时不能并发停止或重新加载。HTTP `timeout_secs` 仅限制控制端等待，不取消目标方法，也不自动重发。超时或命中断点后，用 `jvm.events/frames/resume` 继续排查，必要时 terminate；超时不代表调用没有执行。worker 调用可配合 JVM 断点；EDT 调用暂停时界面也会暂停。

本能力不包含任意 JVM 表达式求值、对象构造器调用或模块访问限制绕过。

完整沙箱启动、JVM/CDP 调试及清理契约见 [Debugger 使用手册](../skills/jtools-plugin-development/references/debugger.md)。
