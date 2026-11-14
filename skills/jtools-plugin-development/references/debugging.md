# 调试与故障定位

Java 可在 JTools 原有开发面板选择模块、按需安装 SDK 依赖并运行预览；Gradle/Maven 同步完成后再编译。日常 IDE 正式安装与独立沙箱调试是两条不同路径：调试优先阅读 [Debugger 完整使用手册](debugger.md)，先自动发现本机 JetBrains IDE 和匹配的 JTools，安装此 Skill 随附 debugger 后，通过 JTools 或外部 MCP 客户端实际暴露的 `sandbox_debug` 执行；支持 stdio、SSE、Streamable HTTP，不限定 IDEA，不要求 JTools AI。只有定向发现未找到时才询问路径并提供下载入口。被测产物只在独立沙箱临时加载；需要正式安装时，另行取得用户确认。不要假定工具已安装或编造工具名。

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
