# 项目缓存与 supportMultiOpens

## 目录

- [选择缓存层级](#选择缓存层级)
- [单开和多开](#单开和多开)
- [项目缓存的生命周期](#项目缓存的生命周期)
- [可编译示例](#可编译示例)
- [常见错误](#常见错误)

## 选择缓存层级

一个插件实例会同时服务多个 Project。可在插件实例字段维护 `ConcurrentHashMap<String, ProjectState>`，键为 projectLocationHash；同项目多个视图可共享业务数据，不同项目必须使用不同上下文。优先实例字段，不必再建 static 全局单例；确需静态缓存也必须在 closeProject/unInstall/appClose 释放，避免 classloader 泄漏。

| 对象 | 可共享范围 | 何时删除 |
| --- | --- | --- |
| 不可变元数据、线程安全无项目客户端 | 插件实例 | appClose / unInstall |
| 项目服务、查询缓存、项目连接 | 相同 projectLocationHash | closeProject；appClose/unInstall 收尾 |
| 面板、树模型、编辑输入、滚动位置、视图任务 | 单个 panel 对象/实例 ID | closePanel |
| AI 会话临时状态 | 相同项目 + sessionId | 明确的业务结束/过期、项目关闭；SDK 无通用 sessionClosed 回调 |

长缓存要有与业务契约相符的有效性和容量策略；不能因为 hash 相同，就缓存 Project、PsiElement、组件及其闭包永久不清理。

## 单开和多开

`supportMultiOpens()` 默认 false：宿主普通打开会尝试选择已有标签。它不代表整个应用中只有一个项目或一个面板；左右容器、不同项目、预览路径仍需独立处理。最稳妥的通用模板每次 createPanel 都返回新对象，让宿主管理标签复用。

如插件明确需要单开 UI 缓存，可用 `(projectLocationHash, panel)` 做项目隔离，并保证同一组件绝不会同时被挂到两个父容器：返回前核对父容器和业务契约；closePanel 只移除该对象对应缓存；关闭后再次创建不能返回已释放组件。仅 `Map<hash, JPanel>.computeIfAbsent` 不足以满足跨容器安全。

`supportMultiOpens() == true`：每次 createPanel 必须返回全新的面板，不能用 `Map<hash, JPanel>`、静态 JPanel 或单一 `currentPanel` 复用 UI。否则新标签会把旧标签的 Swing 组件移走。允许按项目共享业务服务；多个面板的监听器、订阅、任务和可变视图状态仍各自持有、各自释放。

这就是“多开不能缓存”的准确边界：**不能按项目缓存并复用面板实例；项目业务缓存可以保留。** 面板身份请用对象身份表（如 EDT 上的 IdentityHashMap）或自有唯一 ID，不能把 projectLocationHash 当 panelId。

## 项目缓存的生命周期

openProject 可按项目惰性创建服务，也可由无 UI 的函数入口建立项目服务；两条入口走同一业务获取方法。不要在 createPanel 才设置必需的项目路径或函数依赖。

closePanel 精确移除这个 panel 的订阅，不删除共享 ProjectState。closeProject 将对应状态从 map 移除、标记 closed、关闭项目连接并取消任务；已捕获该旧状态的函数/异步任务必须拒绝继续工作。appClose/unInstall 清理全部剩余状态及应用资源，可复用明确的清理方法，不用吞异常制造幂等。

ConcurrentHashMap.computeIfAbsent 与项目关闭不是完整事务；不要让晚到的 call 再 computeIfAbsent 把关闭项目重新创建。函数列举时绑定有效上下文，执行时检查仍有效。复杂异步服务需要锁/取消协议保证调用与关闭的一致性；closed 标记本身不能原子保护“检查后执行”的任意副作用。

## 可编译示例

`assets/examples/multi-open/example/MultiOpenPlugin.java` 提供完整 IPlugin 示例：

- `supportMultiOpens=true`，每次 createPanel 新建面板。
- 插件实例持有按 projectLocationHash 隔离的 ProjectState，面板身份表只追踪单独视图。
- closePanel 只关闭对应面板，closeProject 关闭该项目所有视图并删除业务状态，appClose/unInstall 清理残留。
- 锁保护计数与关闭状态，旧 FunctionCalling 在关闭后显式报错；函数不依赖 UI 打开。
- 不修改基础四套模板的默认单开行为，只有需求明确要多开时才选用此示例。

将此文件复制到选定 Java 模板 `src/main/java/example/`，把入口清单改为 `example.MultiOpenPlugin`。保留模板 icons，构建命令不变。此示例额外依赖 Gson 解析函数 JSON：Gradle 添加 `pluginRuntime("com.google.code.gson:gson:2.11.0")`，Maven 添加普通 compile Gson 依赖，分别由 Shadow/Shade 合并。不要依赖 IDEA 恰好自带的 Gson 版本。

示例没有自动订阅更新：视图只在 createPanel/showPanel 时读取计数，函数执行不会自动刷新所有标签；实际需要联动时为每个 panel 建独立订阅，并在 closePanel 注销。

示例计数器没有真实连接/后台任务；扩展时将相应释放加入明确 owner 的 close，不把项目服务关闭动作放进 closePanel。

## 常见错误

- `static JPanel panel`：跨项目共用，并被 Swing 重新挂载。
- 多开仍 `panels.computeIfAbsent(locationHash, ...)`：同项目多个窗口拿到同一个组件。
- `closePanel` 清空整个项目缓存：关闭一个标签破坏其他标签与 AI 工具。
- `openProject` 每次注册监听而不去重：重复回调造成重复触发和泄漏。
- 只用 sessionName 区分会话：重命名/重名串状态，应使用项目 + sessionId。
- 在静态 map 长期保存 Project/日志器/订阅回调：项目关闭后仍被持有。
- 在 function.call 中复活已关闭项目：晚到调用重建状态并访问失效 Project。
