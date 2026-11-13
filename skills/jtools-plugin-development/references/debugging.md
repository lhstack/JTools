# 调试与故障定位

Java 可在 JTools 原有开发面板选择模块、按需安装 SDK 依赖并运行预览；Gradle/Maven 同步完成后再编译。也可直接构建 JAR，通过原有插件管理安装。没有新增 AI 专用开发工具、安装按钮或 CLI 指令。

| 现象 | 核对 |
| --- | --- |
| 找不到入口 | JAR/ZIP 根的 META-INF/ToolsPlugin.txt、实际类全名、public 无参构造器 |
| ClassNotFound/NoClassDefFound | 依赖树、合并 JAR 内容；嵌套 JAR 不自动加载；SDK/IDE 由宿主提供 |
| UnsupportedClassVersion | 扩展业务 class 目标 Java11，构建运行 JDK 与 IDEA 平台 class 版本匹配 |
| NoSuchMethodError | 用到的 SDK/IDE API 是否存在于目标宿主；依赖重复、版本冲突 |
| UI 卡死 | EDT 是否执行网络/磁盘/重计算；避免 invokeAndWait 互等 |
| JS 页面空白 | pluginInfo.json、indexPage、相对静态路径和 JCEF Console/Network |
| JTools 未定义 | 是否在宿主中打开、注入是否完成、全局名称大小写 |
| 文件读取失败 | 当前项目与实际路径、桥接 API 的权限及错误；不要假数据兜底 |
| 多开后旧标签变空/跨项目串状态 | createPanel 是否返回同一组件、是否只按项目 hash 缓存 UI、项目服务与面板状态是否分离 |
| 函数没有会话上下文 | Project/String functionCallings 重载链、五参 call 的默认转发、是否缓存旧会话闭包 |
| 关闭后仍运行 | closePanel/closeProject/unInstall/appClose 对应释放资源 |

开发工具按当前 IDE 项目工作，进入目标工程再操作。先确认真实错误及根因所属层，再改模板或业务；不通过复制平台类、吞错或假成功绕过。报告普通浏览器和实际 JCEF 的验证范围区别。
