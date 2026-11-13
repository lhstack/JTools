plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

repositories { mavenCentral() }

group = "example"
version = "1.0.0"

val sdkFile = file(providers.gradleProperty("jtoolsSdk").orElse("${System.getProperty("user.home")}/.jtools/sdk/sdk.jar").get())
require(sdkFile.isFile) { "缺少 JTools SDK: $sdkFile；用 -PjtoolsSdk 指定实际 SDK JAR" }

// 仅合并显式业务依赖，宿主 SDK / IDEA 平台不得进入此配置。
val pluginRuntime by configurations.creating
configurations.implementation { extendsFrom(pluginRuntime) }

dependencies {
    compileOnly(files(sdkFile))
    // 第三方运行依赖使用 pluginRuntime("group:artifact:version")，见依赖手册。
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("plugin")
    configurations = listOf(pluginRuntime)
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
