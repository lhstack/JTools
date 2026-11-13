# Swing / Maven 模板

复制 `assets/templates/maven` 的全部文件到选定插件目录。修改 groupId/artifactId、Java 包名、入口清单与插件元数据；资源图标逻辑尺寸分别为 48×48 和 14×14。

使用 Maven 3.8+ 与 JDK11+。在插件根目录执行：

```sh
mvn -Djtools.sdk=/path/to/sdk.jar clean package
jar tf target/jtools-demo-1.0.0-plugin.jar
```

模板固定编译器 3.13.0、Shade 3.6.2；首次构建需要下载插件。发行物是 `target/jtools-demo-1.0.0-plugin.jar`，不是未合并第三方依赖的原始 JAR。实际改名后以产物文件为准。

使用 Java/Swing、IPlugin 的 String locationHash 重载及 Helper 已封装的宿主能力，无需 IntelliJ Platform 插件；见 [Helper 手册](helper.md)。直接操作 Project/PSI/Editor 等平台类型时使用 gradle-idea 模板，不乱塞平台 JAR。

SDK 参数仅引用本地文件。模板中的 systemPath Maven 提示不代表打包依赖；按 dependencies.md 排查，验证 JAR 未复制 SDK 类。缺少 mvn 时报告工具链缺失，让维护者提供已有 Maven 或经过允许再安装。
