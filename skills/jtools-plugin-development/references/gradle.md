# Swing / Gradle 模板

复制 `assets/templates/gradle`。纯 Swing 使用 Gradle 8.6、JDK17+（Shadow 8.3.6），编译为 Java11；若使用更高 Gradle 版本，核对它支持的运行 JDK。

模板不附来源不明的 wrapper 二进制。使用当前已安装 Gradle，或项目既有且可信的 wrapper：

```sh
gradle -PjtoolsSdk=/path/to/sdk.jar clean shadowJar
jar tf build/libs/jtools-demo-1.0.0-plugin.jar
```

需要 wrapper 时，在确认网络与工具链后用已有 Gradle 执行 `gradle wrapper --gradle-version 8.6`，把生成的 wrapper 文件一并纳入新工程。Windows 对应使用 `gradlew.bat`；已有项目沿用其配置。

SDK compileOnly，业务依赖 pluginRuntime，`shadowJar` 只合并 pluginRuntime。不默认覆盖重复资源；含 ServiceLoader 或重复 LICENSE 时按依赖手册处理。产物是 `*-plugin.jar`，assemble 也依赖该任务。

需要通知、文件选择、IDE 图标和工具栏时可直接使用 SDK [Helper](helper.md)，无需因此引入 IntelliJ Platform 插件；直接平台对象操作才切换 IDEA 模板。
