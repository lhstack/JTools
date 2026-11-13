# SDK 与第三方依赖

## 获取 SDK

SDK 是 JTools Java 扩展接口 JAR，不是 IDEA 插件包。按以下来源核实其存在与版本：

- 已安装宿主在开发面板执行“安装开发依赖”后，SDK 通常位于用户目录 `.jtools/sdk/sdk.jar`；原有面板仍可使用，但 AI 不应假设已执行。
- 持有 JTools 发行 ZIP 时，解开其 `jtools/lib/jtools-*.jar`，从内层 JAR 提取 `META-INF/sdk.jar`。先用 `jar tf` 核实，不能拿主插件 JAR 充当 SDK。
- 在 JTools 源码工程构建 SDK 时沿用该工程的配置；取 `sdk/build/libs/sdk-*.jar` 前核对是否包含 `com/lhstack/tools/plugins/IPlugin.class`，不要选 sources JAR。

用 `jar tf sdk.jar` 检查接口；缺失则报错。模板允许指定真实 SDK 路径：Maven `-Djtools.sdk=/path/sdk.jar`，Gradle `-PjtoolsSdk=/path/sdk.jar`。路径含空格时给整个参数加引号。

SDK 不是公开 Maven 仓库的既定坐标，不执行虚构的 `dependency:get`。Maven 模板中的 `local.jtools:sdk:1` 仅标记本地 systemPath。不要随扩展打包 SDK、IntelliJ 平台、JBR 或 JTools 主插件类。

纯 SDK 工程可用 [Helper](helper.md) 的 Java/Swing 签名访问已封装宿主能力，不需要为了这些方法引入 IDEA 构建插件；运行环境仍须是 JTools/IDE。

## 纯 Swing 依赖

Maven 中第三方依赖用普通 compile/runtime scope，`maven-shade-plugin` 生成 `*-plugin.jar` 并合并 ServiceLoader 文件。SDK 使用 system scope，仅编译。检查最终 JAR 不含 SDK 类。

## Gradle Shadow（Swing 与 IDEA）

两套 Gradle 模板均固定使用 `com.gradleup.shadow` 8.3.6；执行 `shadowJar` 产出 `*-plugin.jar`。不要改回手工 zipTree 拼包。

模板用独立 `pluginRuntime` 维护需要随插件交付的第三方运行依赖，`implementation` 从它继承以参与编译。把依赖加入：

```kotlin
dependencies {
    pluginRuntime("org.apache.commons:commons-lang3:3.17.0")
}
```

只在业务实际需要该库时添加。Shadow 的 `configurations = listOf(pluginRuntime)` 仅合并这部分依赖，不合并 IDEA 平台 runtimeClasspath。SDK 用 compileOnly，IDEA API 由 IntelliJ Platform Gradle 插件提供；IDEA 自带的库不要重复声明到 pluginRuntime。确认目标宿主确实带该库且版本/API 可用，否则作为第三方库经 Shadow 打包，必要时 relocation 避免与宿主冲突。

Shadow 已配置 `mergeServiceFiles()`；服务描述符允许重复并合并，其他重复项默认 FAIL。不要 `minimize()` 自动删除反射、ServiceLoader 或 JNI 所需的类。需要包重定位时：

```kotlin
tasks.shadowJar {
    relocate("org.apache.commons.lang3", "example.internal.shadow.lang3")
}
```

只重定位自己的第三方依赖，不重定位 com.intellij、JTools SDK、Java 标准库或公共 API 类型。IDEA Java、Gradle、Maven 等可选 API 还需在平台依赖中声明对应 bundledPlugin，并确认目标平台确实带该插件。

## 服务、冲突与许可证

多依赖含 `META-INF/services` 时按行合并，不丢 provider；同名 class 查依赖树，必要时显式 relocation，不能任选一个覆盖。签名 `.SF/.RSA/.DSA` 在重打包时删除，LICENSE/NOTICE 按许可证保留。不要把嵌套 JAR 当成已加载的依赖。

Gradle 用 `dependencies --configuration pluginRuntime`，Maven 用 `dependency:tree` 排查。依赖下载失败应报告仓库与错误，不自动切换不可信仓库或使用假实现。
