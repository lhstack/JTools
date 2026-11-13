# Java 入口、生命周期与线程

## 目录

- [入口和兼容性](#入口和兼容性)
- [实例、项目、面板与会话](#实例项目面板与会话)
- [生命周期职责](#生命周期职责)
- [调用顺序和真实边界](#调用顺序和真实边界)
- [Project 与 String 重载](#project-与-string-重载)
- [线程与错误](#线程与错误)
- [多项目多开验收](#多项目多开验收)

## 入口和兼容性

JAR/ZIP 根的 `META-INF/ToolsPlugin.txt` 逐行指定 IPlugin 实现的全限定名；入口类须 public，带 public 无参构造器。更新包名同时修改入口清单。不要使用 classpath.index 代替入口。

必须实现 `pluginIcon/pluginTabIcon/pluginName/pluginDesc/pluginVersion`。图标逻辑尺寸为 48×48、14×14；纯 Swing 的 ImageIcon 用 PNG，IDEA 可用 IconLoader 加载 SVG。

`pluginType()` 默认 JAVA（有 UI），JAVA_NON_UI 表示不提供面板。`support(Integer, IdeInfo)` 返回 Support，加载器先检查兼容性再执行 install；新实现使用该双参版本，不仅覆盖已弃用的单参 support。`installRestart()` 默认 false；需要重启时如实声明，不能用重启代替资源释放。精确成员以当前 SDK 为准。

## 实例、项目、面板与会话

同一 IDE 进程内，PluginManager 保存插件实例，多个项目共享该实例。因此普通实例字段已经能跨项目共享，不需要为了共享再加 static 单例。不同 IDE 进程的内存互不共享；locationHash 也不是进程间同步方案。

| 层级 | 建议标识 | 生命周期/内容 |
| --- | --- | --- |
| 应用/插件实例 | 插件自身 | 不可变元数据、线程安全客户端、应用公共资源 |
| 项目 | `project.getLocationHash()`；SDK String 参数名为 `locationHash` | 项目业务状态、项目连接、配置快照 |
| 面板实例 | `pluginPanel` 对象身份或插件自建 panelId | Swing 组件、监听器、视图订阅、面板任务 |
| AI 会话 | `(projectLocationHash, sessionId)` | 确有需求的会话状态；sessionName 仅展示 |

`projectLocationHash` 是手册中的业务变量名，不是新增 SDK 方法。它标识项目，不能区分同一项目的多个面板，也不能当目录路径使用。需要真实根目录时可用 `Helper.getProjectBasePath(locationHash)`，或在 IDEA 侧检查 `project.getBasePath()`；检查真实返回值，不从 hash 猜路径。Helper 也提供图标、通知、选择文件等封装，见 [Helper 手册](helper.md)。

具体缓存选择和可编译多开示例见 [项目缓存与多开](state-and-multi-open.md)。插件函数独立于面板生命周期，见 [函数与工具栏](functions-and-actions.md)。

## 生命周期职责

| 方法 | 职责 | 释放/禁止事项 |
| --- | --- | --- |
| 构造器 | 轻量对象初始化 | 不访问尚未绑定的项目，不启动每项目任务 |
| `install()` | 插件实例加载后的应用资源初始化 | 宿主启动重新加载也会调用，不等于只执行一次的首次安装迁移 |
| `openProject(Project, Logger, Runnable)` 或 String 重载 | 建立项目上下文，保存项目日志器/必要服务；Runnable 用于请求打开面板 | 可重复到达，避免重复监听器和重复线程；不要假设只调用一次 |
| `createPanel(Project)` 或 String 重载 | 返回当前请求的面板；多开必须创建新组件 | 返回有效 JComponent；UI 创建在 EDT；不借此初始化所有函数的业务服务 |
| `tabPanelActions(Project, panel)` / `swingTabPanelActions(hash, panel)` | 给该面板绑定工具栏动作 | 动作捕获传入的 panel，不通过 hash 猜“当前面板” |
| `showPanel(project, panel)` 或 String 重载 | 当前面板创建后启动/更新其展示状态 | 当前宿主创建时调用，不保证每次切换标签、重新选中均调用 |
| `closePanel(project, panel)` 或 String 重载 | 释放这个 panel 的订阅、监听器、任务、UI 句柄 | 不清空其他面板或整项目共享状态；按传入对象身份移除 |
| `closeProject(project)` 或 String 重载 | 删除该项目缓存，取消项目任务，关闭其连接 | 不清理其他项目；释放残留面板并让旧函数/回调失效 |
| `appClose()` | 清理所有项目残留和应用级资源 | 不依赖 closeProject 一定先到；重复清理应安全 |
| `unInstall()` | 释放插件安装级资源、解除注册 | 当前卸载路径也调用 appClose，避免重复释放报错；不擅删用户持久数据 |

关闭面板与关闭项目有不同职责：面板关闭后项目仍可被 AI 函数使用，项目连接不能随着一个标签关闭而失效。需要面板可见性控制的订阅归属面板；需要整个项目存续的资源归属项目。幂等清理来自回调组合，不是吞掉关闭错误。

## 调用顺序和真实边界

通常加载为：构造 → support 检查 → install。项目打开通知为 openProject；打开 UI 通常为 createPanel → tabPanelActions → showPanel。普通再次选择已存在标签可能直接返回，不再创建，也不重新调用 showPanel。

项目关闭：宿主通知关闭此项目的插件面板，然后调用 closeProject。IDE 退出还会调用 appClose。当前卸载路径：发布卸载事件关闭面板 → 对打开项目调用 closeProject → appClose → unInstall → 移除插件。不要把观察到的顺序当作所有异常、开发预览和未来版本的事务保证；每一层释放自身持有的资源。

项目初始化与主工具窗口创建均可能通知 openProject，因此初始化必须按项目去重。开发预览也有自己的创建/销毁路径，不能依赖“必须点开某个面板后函数才能工作”。

## Project 与 String 重载

常用生命周期的默认 Project 重载调用对应的 String locationHash 重载：openProject、createPanel、showPanel、closePanel、closeProject。纯 Swing 实现 String 版；IDEA 实现 Project 版。不要把 String 版和 Project 版都写成互相调用而形成递归。

函数列举有特别的默认转发关系，不能类推：

```text
functionCallings(sessionName, sessionId, Project)
  → functionCallings(Project)
  → functionCallings(locationHash)

functionCallings(sessionName, sessionId, locationHash)
  → functionCallings(locationHash)
```

Project 会话重载默认不会转给 String 会话重载。如果纯 Swing 函数需要会话参数，可覆盖两个会话入口并显式委托；或者在 FunctionCalling.call 的会话重载处理会话。完整范例见函数手册。

## 线程与错误

UI 组件和面板身份表在 EDT 操作。函数/后台任务可能并发访问项目业务状态；ConcurrentHashMap 只保护 map，不保护 value 的可变字段。用原子字段、锁或不可变快照保护状态。关闭项目时标记上下文 closed，阻止晚到回调继续写入；异步结果回 UI 前检查项目/面板是否仍有效。

项目初始化、函数列举、界面创建不能执行长时间网络请求。Project/PSI 访问用 read/write action 及 smart mode，磁盘/网络在后台；不要假设 install、appClose、函数列举和 FunctionCalling.call 都在 EDT。只在 UI 边界调度 EDT，不把耗时函数整个放 EDT。

错误在能处理的层展示/返回，不捕获后假成功；函数返回必须是合法 JSON。关闭失败可以继续清理其他独立资源，但必须保留并报告真实错误。

## 多项目多开验收

1. 同一 IDE 打开 A、B 项目；验证项目状态、日志、路径和函数调用互不串用。
2. A 连续创建两个面板，检查对象身份不同、输入/视图独立；关闭一个，另一个及项目函数仍可用。
3. 关闭 A，确认 A 的监听器、缓存、连接和任务清理，B 正常；旧函数/晚到结果不得重建 A 的状态。
4. 重新打开 A，确认得到新上下文；切换标签不能重复创建监听器；无面板时函数也能执行。
5. 卸载与应用退出分别验证，重复清理安全；AI 两会话并发执行时按项目和 sessionId 隔离。

以上为验收场景，不是已经验证过的运行结论；交付时注明实际执行情况。
