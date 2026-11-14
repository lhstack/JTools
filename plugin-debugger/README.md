# 插件沙箱调试器

新增命令通过现有 `sandbox_debug` 工具执行。需要同时更新 JTools 主插件和 debugger JAR：主插件沙箱宿主持有被测插件的真实实例及 ClassLoader；debugger 负责会话隔离和 RPC 转发。

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
