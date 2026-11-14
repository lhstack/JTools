# JTools 扩展插件示例

四个目录均为独立工程，不属于宿主根 Gradle 多模块构建。实现依据 `skills/jtools-plugin-development`，不依赖旧的 `plugin-example`。SDK、IDE 平台及 JTools 主插件不随扩展打包。

| 目录 | 内容 | 发行产物 |
| --- | --- | --- |
| `js` | 原生 HTML/JS，输入交互与真实 JTools 项目路径桥接 | `build/jtools-js-example-1.0.0-plugin.zip`（相对 examples） |
| `maven` | Java/Swing，Maven Shade | `maven/target/jtools-maven-example-1.0.0-plugin.jar` |
| `gradle` | Java/Swing，Gradle Shadow | `gradle/build/libs/jtools-gradle-example-1.0.0-plugin.jar` |
| `gradle-idea` | IDEA Project API，Gradle Shadow | `gradle-idea/build/libs/jtools-gradle-idea-example-1.0.0-plugin.jar` |

## 构建

在仓库根目录执行。需要 Python 3、Maven 3.8+、JDK 17+；使用仓库已有 Gradle wrapper。

```sh
./gradlew :sdk:jar -x buildSearchableOptions
SDK="$PWD/sdk/build/libs/sdk-1.0.0.jar"
(cd examples/maven && mvn "-Djtools.sdk=$SDK" clean package)
./gradlew -p examples/gradle "-PjtoolsSdk=$SDK" clean shadowJar
./gradlew -p examples/gradle-idea "-PjtoolsSdk=$SDK" clean shadowJar
python3 examples/js/package.py
```

也可使用已安装且接口齐全的 SDK，通过上述参数指定实际文件，不依赖个人绝对路径。JS 打包默认拒绝覆盖旧 ZIP，重新打包前先移走旧产物。没有 `mvn` 时使用已有 Maven 的完整可执行路径，不自动安装。

Java 示例生成 Java 11 字节码并保留行号及局部变量信息。IDEA 示例默认使用 2022.3 编译基线，也可显式传 `-PintellijPlatformLocalPath=<IDE目录>`；这不代表主插件支持 IDEA 2022.3，JTools 宿主仍要求 251 起。

## 验证与调试

所有示例的“应用”按钮将输入去除首尾空白后显示为 `已应用：内容`。Java 组件名为 `example-input`、`example-apply`、`example-result`；JS 使用同名 DOM id。JS 的“读取当前项目”必须在真实 JTools 中使用；桥接不存在或调用失败时显式展示错误，不模拟成功。

Java 断点可设置在各入口类的 `formatMessage` 方法返回行，JS 可设置在 `app.js` 的 `formatMessage`。JVM 调试使用实际类名与当前源码行号，不在文档固化易变行号。

先构建以上产物，再执行可选的包/交互验证：

```sh
node --test examples/js/app.test.cjs
./gradlew :plugin-debugger:test -PdebuggerTestExamples=true -PdebuggerTestJcef=true
```

示例包测试使用真实 JAR 和 Swing 组件；IDEA Project 仅用接口测试替身提供名称，不等于真实 IDE 验收。真实 JCEF 测试不提供假的 JTools 桥接。

沙箱现场验收需在当前会话暴露 `sandbox_debug`，或提供有效的独立 IDE 启动环境：用 `help` 核实契约，`start` 后等待 `ping`，逐个 `run` 产物，操作输入/按钮，设置断点并恢复，最后 `stop` 和 `terminate`。每次运行前停止旧产物，不安装到正式插件数据。沙箱参数、日志、token 和个人配置不得提交到示例目录。

包内容、Swing 交互及独立 JCEF 测试通过不能替代宿主桥接和完整 IDE 生命周期验收。具体结果以本次实际测试报告为准。
