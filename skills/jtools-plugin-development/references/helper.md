# Helper：纯 SDK 工程使用宿主能力

## 选择边界

`com.lhstack.tools.plugins.Helper` 属于 JTools SDK，在宿主内部封装部分 IDEA API。Maven/Swing Gradle 工程只引用 SDK，即可调用以 Java/Swing 类型为参数和返回值的 Helper 方法，不需要仅为文件选择、通知、工具栏或 IDE 图标增加 IntelliJ Platform Gradle 插件。

区分“没有在扩展工程引入 IDEA 构建依赖”和“运行时没有 IDEA”：Helper 运行仍依赖 JTools 所在 IDE。不要在普通 Java main 中初始化 Helper 来冒充 IDE 验证；其静态初始化会读取 ApplicationInfo。

如果要直接操作 Project、PSI、VFS、Editor 等平台类型，或 Helper 尚未封装的能力，使用 gradle-idea 模板和相应编译依赖。不要强转 Helper 返回的 JComponent 为平台实现类绕过依赖，也不要把 IDEA 平台/SDK 复制进 Shadow 包。

## 能力与真实契约

| 方法 | 用途与返回 | 线程、失败及注意事项 |
| --- | --- | --- |
| `getIdeInfo()`、`JTOOLS_VERSION` | IDE 信息、JTools 数字版本 | 来自当前宿主，不是用户输入；不修改公共版本字段 |
| `findIcon(path, ClassLoader)` / `findIcon(path, Class<?>)` | 用 IDEA IconLoader 读图标，返回 Swing Icon，可加载 SVG | 在宿主使用；显式核对资源路径、48×48/14×14 尺寸与空结果 |
| `actionButton(...)` | IDEA 风格按钮，返回 JComponent；有标题、图标、尺寸、选中态等重载 | UI 创建在 EDT，当前适配器 update 也在 EDT；回调给事件上下文的项目 hash，缺少 Project 时是空串 |
| `createActionToolbar(target, horizontal, place, Action...)` | 将 SDK Action 包成 IDEA 工具栏，返回 JComponent | target 指向实际面板；查询 title/isSelected 当前在 BGT，不能直接读取 Swing；业务点击绑定该 panel |
| `languageTextField(...)` | 指定语言 ID、hash、默认内容、单行/只读的编辑组件 | UI 创建在 EDT；通过 setValueSupplier 接收文本 setter，documentChangeListener 收变化；项目未找到返回 null |
| `openFileInEditor(hash, Path)` | 按项目打开文件 | 内部 invokeLater，void 只表示提交；文件不存在在异步路径抛错，导航失败写宿主日志；项目未找到不通知调用者 |
| `notify(hash, title, content, type)` | 向指定项目发通知 | type 使用 INFORMATION/WARNING/ERROR/IDE_UPDATE；内部 valueOf，非法值报错；未找到项目不发通知 |
| `getSysLogger(hash)` | 获取此项目宿主 Logger | 项目或日志器不存在可能为 null，业务需要时显式检查；也可使用 openProject 传入的 Logger |
| `getProjectBasePath(hash)` | 查项目根目录 String | 项目未找到会抛错，匹配项目自身 basePath 也可能为 null；hash 不是路径 |
| `treeSpeedSearch(JTree, canExpand, Function<TreePath,String>)` | 给 Swing 树安装快速搜索 | 在 EDT 创建/安装，树的归属仍是面板，不全局复用 |
| `chooseFile` / `chooseFiles` | 选择单/多个文件，filter 和回调使用 String 路径 | 内部 invokeLater；用户取消或未找到项目不回调，不等同错误/成功；回调在 UI 路径，不执行大文件 IO |
| `chooseDirector` | 选择目录（SDK 方法名就是 Director） | 参数与单文件选择类似，不猜测不存在的 chooseDirectory 方法 |
| `chooseSaveFile` | 选择保存位置，Consumer<File> 收到结果，可传扩展名 | 内部 invokeLater；用户取消不回调，不会替你写文件，也不自动表达业务覆盖授权 |
| `restart()` | 请求宿主重启 | 有实际副作用，只在用户明确要求且确认工作已保存时使用 |

方法签名以实际 SDK 为准，不根据名称猜额外返回码、取消通知或可等待 Future。异步方法在调用处 try/catch 只能捕获提交时异常，不能捕获后来在 EDT 中发生的错误；函数结果只能说“已请求打开/已提交”，不能据 void 返回声称“文件已成功打开”。需要明确完成态而 Helper 未提供时，走具备相应契约的 IDEA API，而不是伪造成功。

## 面板示例

见 `assets/examples/helper/example/HelperPanel.java`，只使用 SDK 与 Java/Swing 类型，可直接放入 Maven/Swing Gradle 模板 `src/main/java/example/`，在 createPanel(String locationHash) 中返回 `new HelperPanel(locationHash)`。

示例提供 IDE 图标、文件选择、通知和编辑器打开请求；不操作 Project/PSI，不依赖 IDEA 构建插件。文件选择回调使用面板创建时绑定的 hash，不因当前焦点切换而把操作路由到其他项目。关闭面板时调用其 close，阻止迟到回调操作已关闭 UI。HelperPanel 本身不持有项目级全局缓存。插件入口对接方式（保留模板已有元数据实现）：

```java
@Override
public JComponent createPanel(String locationHash) {
    return new HelperPanel(locationHash);
}
@Override
public void closePanel(String locationHash, JComponent panel) {
    if (!(panel instanceof HelperPanel)) throw new IllegalArgumentException("面板类型不匹配");
    ((HelperPanel) panel).close();
}
```

createPanel/closePanel 均在 EDT 执行；多开时每次 new，关闭时处理传入的具体面板，不按 hash 找唯一视图。

actionButton 自带的 Consumer<String> 收到的是事件项目上下文；用于操作固定面板时优先使用面板已经绑定的 hash。如果业务要随当前焦点项目操作，必须显式校验回调 hash 非空且业务允许，不把空串兜底成另一个打开项目。

## 语言编辑框与释放边界

签名包含 `Consumer<Consumer<String>> setValueSupplier`、`Consumer<Runnable> dispose` 和 `Consumer<String> documentChangeListener`。当前 Helper 实现调用 setter supplier 和变化回调，但 **没有使用 dispose 参数**。不要编造 closePanel 时已有 release Runnable 可调用，也不要因为 `JComponent.removeAll()` 就声称已释放底层 Editor。

需要可验证的显式 Editor 释放时，先使用 IDEA 模板的直接 API 明确 owner/disposable，或修复宿主 Helper 的生命周期契约后再使用对应 SDK。此 Skill 只记录现状，不用反射、强制转换或吞错在扩展层修补宿主遗漏。实际语言支持由目标 IDE 安装的语言插件决定；SDK 传入的是 Language ID，不能保证任意字符串都获得语法支持。

多个面板共享项目业务服务但不共享编辑组件。setter 和变化回调保留时归属该面板；关闭后停止使用并解除插件自己的引用。样例未演示 languageTextField，因为当前 dispose 契约不足以提供完整的显式释放示例。
