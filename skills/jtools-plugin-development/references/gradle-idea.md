# IDEA API / Gradle 模板

复制 `assets/templates/gradle-idea`。此工程是运行于 JTools 内的扩展，不是独立 JetBrains 插件，不添加 `META-INF/plugin.xml`，不使用 `buildPlugin` 产物替代 JTools 扩展。

基线：Gradle 8.6+、IntelliJ Platform Gradle 插件 2.7.2、IDEA Community 2022.3；使用 JDK17 构建并生成 Java11 字节码。JTools 宿主当前支持 251–263，选择较老 API 基线是为了减少新增 API 依赖，并不代表宿主支持 2022.3。

```sh
gradle -PjtoolsSdk=/path/to/sdk.jar clean shadowJar
jar tf build/libs/jtools-idea-demo-1.0.0-plugin.jar
```

首次构建需要下载插件和平台。可显式使用已安装 IDE：

```sh
gradle -PjtoolsSdk=/path/to/sdk.jar -PintellijPlatformLocalPath=/path/to/idea clean shadowJar
```

本地平台改变后按其字节码要求使用合适构建 JDK；测试较新本地平台通过不代表 2022.3 基线已验证。记录使用的精确 IDE/JDK。

入口实现 `createPanel(Project)`。读取 PSI/VFS 使用 read action，需要索引时等待 smart mode；写入 PSI、文档和项目模型使用相应 write command；UI 在 EDT，网络和磁盘放后台。关闭项目后不得继续写入已释放对象。

平台依赖仅供开发与宿主 API 使用，不打入业务 JAR；模板 `shadowJar` 默认只含本工程类和资源。有第三方业务库按 dependencies.md 加入独立 pluginRuntime，经 Shadow 合并。

Shadow 任务显式依赖 generateManifest，保证 IntelliJ Platform 插件生成的清单先存在；不要在重构模板时删除该任务依赖。

如果需求仅是 Helper 已封装的通知、文件选择、图标/工具栏等，可沿用纯 SDK Maven/Swing Gradle 工程，先读 [Helper 手册](helper.md)，不为这些功能单独引入平台插件。
