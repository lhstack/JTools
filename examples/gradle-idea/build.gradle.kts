plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("com.gradleup.shadow") version "8.3.6"
}
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
group = "com.lhstack.examples"
version = "1.0.0"

val sdkFile = file(providers.gradleProperty("jtoolsSdk").orElse("${System.getProperty("user.home")}/.jtools/sdk/sdk.jar").get())
require(sdkFile.isFile) { "缺少 JTools SDK: $sdkFile；用 -PjtoolsSdk 指定实际 SDK JAR" }
val pluginRuntime by configurations.creating
configurations.implementation { extendsFrom(pluginRuntime) }
dependencies {
    compileOnly(files(sdkFile))
    intellijPlatform {
        val localPlatform = providers.gradleProperty("intellijPlatformLocalPath").orNull
        if (localPlatform != null) local(localPlatform) else intellijIdeaCommunity("2022.3")
    }
}
intellijPlatform { buildSearchableOptions.set(false) }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    options.isDebug = true
}
tasks.shadowJar {
    dependsOn(tasks.named("generateManifest"))
    archiveClassifier.set("plugin")
    configurations = listOf(pluginRuntime)
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
