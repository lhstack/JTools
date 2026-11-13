# 插件函数、会话与面板动作

## 目录

- [FunctionCalling 契约](#functioncalling-契约)
- [函数列举与项目绑定](#函数列举与项目绑定)
- [会话重载](#会话重载)
- [面板工具栏](#面板工具栏)
- [并发与验收](#并发与验收)

## FunctionCalling 契约

IPlugin.functionCallings 返回 `List<FunctionCalling>`；宿主将其包装为模型工具，模型侧名称带宿主前缀，不要假设最终 tool name 与 name() 完全相同。

| 方法 | 必须满足 |
| --- | --- |
| `name()` | 非空、同插件内唯一、稳定；建议字母数字下划线，不塞会话名或路径 |
| `description()` | 说明业务用途、输入、输出和副作用，默认只是 name()，实际开发应明确描述 |
| `parameters()` | 合法 JSON Schema 字符串，通常顶层 object，写 properties/required/additionalProperties |
| `call(String argumentsJson)` | 必须实现，解析并校验业务输入，返回合法 JSON 字符串 |
| `call(args, sessionName, sessionId)` | 可覆盖，默认转到单参 call |
| `call(args, sessionName, sessionId, provider, model)` | 可覆盖，默认转到三参 call；provider/model 是当前调用上下文 |

用实际 JSON 库处理转义和序列化，不能用字符串拼接插入未经转义的用户输入。宿主最终用 JsonParser 解析返回文本，普通文字不是合法 JSON；不返回虚构成功。错误可以抛出由上层处理；业务明确把失败作为结果时返回有 error/status 的 JSON，不吞掉异常。

图标/元数据类和声明函数不能隐式执行业务写入。函数列举是查询，不启动网络任务，不触发 UI，不因用户打开配置页面就执行操作。函数可能在面板未创建或已关闭时使用，业务服务不能藏在组件里。

## 函数列举与项目绑定

普通入口可覆盖 `functionCallings(Project)`，纯 Swing 覆盖 `functionCallings(String locationHash)`。绑定项目服务到返回的函数对象；call 参数本身没有 Project/hash，不能从“最近打开的面板”推断项目。

当前 `PluginFunctionToolSupport.listFunctions` 会优先尝试指定项目，再尝试其他已打开项目，选首个非空列表，最后尝试 String 重载。因此**返回空列表不能当成项目访问授权边界**，也不能假定函数列举只针对当前项目调用一次。项目敏感操作要在业务边界校验明确绑定的目标及授权；需要严格当前项目专属路由时，应先处理宿主路由规则，Skill 示例不能伪造宿主保证。

完整多开示例的工具仅对已绑定项目状态计数，不访问文件、网络或项目敏感资源；实际插件用其模式时必须审查上述路由边界。不可将另一个项目的第一个可用服务当作本项目服务。

函数条目按 name 去重，重复名称会被宿主折叠，不把重名当重载。插件函数是否向 AI 暴露受 Agent/环境能力配置影响；实现函数不代表所有模型都已经启用。

## 会话重载

宿主在会话名和 ID 都非空时调用五参 call，否则调用单参 call。兼容无会话场景时在单参实现合理业务路径；确需会话而缺少时显式报错。sessionId 用作身份，sessionName 可变且可能重名；provider/model 仅当前调用观测，不用来路由另一个会话。

SDK 默认调用链：

```text
functionCallings(name, id, Project) → functionCallings(Project) → functionCallings(hash)
functionCallings(name, id, hash) → functionCallings(hash)
call(args, name, id, provider, model) → call(args, name, id) → call(args)
```

纯 Swing 若在“列举阶段”需要会话信息，显式将两个入口委托到自己的同一方法（不可把默认 Project 链当作自动转发到 String 会话版）：

```java
@Override
public List<FunctionCalling> functionCallings(String name, String id, Project project) {
    return functionCallings(name, id, project.getLocationHash());
}
@Override
public List<FunctionCalling> functionCallings(String name, String id, String hash) {
    return buildFunctions(hash, name, id); // 插件自己实现，必须覆盖无会话情况
}
```

此片段引用 Project，工程应具备相应平台编译依赖；纯 Swing 不需要列举阶段会话时，保留 String 普通入口，在 FunctionCalling.call 的会话重载读取上下文即可，不为这件事随包复制 IDEA 平台。

缓存函数对象时不能捕获旧 sessionId/provider/model 并跨会话复用；或每次列举创建绑定对象，或以稳定项目上下文 + 调用时会话参数工作。SDK 没有通用会话关闭通知，确需会话缓存时定义显式结束/有效期/容量，关闭项目和卸载时清理；不无限累积所有聊天历史。

## 面板工具栏

IDEA 实现 `tabPanelActions(Project, JComponent)` 返回 AnAction；纯 Swing 实现 `swingTabPanelActions(String locationHash, JComponent)` 返回 SDK Action。默认 Project 工具栏会把 String 版动作转换为 AnAction。

SDK Action：icon/title 必须实现，description 默认 title，actionPerformed 处理点击，isSelected 默认 false。当前适配器 update 在线程 BGT 调用 title/isSelected 等查询；不要在这些方法直接读取 Swing 组件或修改 UI。将状态放在原子字段/安全快照中；按钮 actionPerformed 若涉及 UI 应确认 EDT，耗时工作另起后台任务。IDEA 自定义 AnAction 按实际职责声明 ActionUpdateThread。

动作必须绑定传入 panel；多开时不能从 `Map<projectHash, JPanel>` 取唯一面板。closePanel 后禁用/移除订阅，晚到异步结果检查该实例仍打开。

## 并发与验收

函数可并发运行，不能假设 EDT 或全局串行；项目服务自身负责同步，UI 更新投递到 EDT。长任务遵守工具运行时超时与取消契约；FunctionCalling 本身没有通用 cancel 参数，不在手册声称宿主必定能中断所有插件线程。

核对 schema 与输入校验一致、返回 JSON 可解析、两项目状态隔离、两会话不串上下文；面板关闭后函数可用，项目关闭后旧函数明确失效；参数非法不会产生副作用。枚举和 call 的路由、线程与重载应按当前宿主源码复核，不能根据函数名臆测。
