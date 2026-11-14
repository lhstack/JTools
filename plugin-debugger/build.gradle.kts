import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.lhstack"
version = "0.1.0"
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    compileOnly(project(":sdk"))
    intellijPlatform {
        val localPlatform = providers.gradleProperty("intellijPlatformLocalPath").orNull
        if (localPlatform != null) local(localPlatform) else intellijIdeaCommunity("2022.3")
    }
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.java-websocket:Java-WebSocket:1.6.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    // 单测只需要 SDK 编译输出，不消费 IntelliJ 发行变体，避免触发 buildSearchableOptions。
    testImplementation(files(rootProject.project(":sdk").tasks.named<JavaCompile>("compileJava").flatMap { it.destinationDirectory }))
}
tasks.withType<JavaCompile> { options.release.set(17) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-modules=jdk.jdi,jdk.httpserver")
    doFirst { systemProperty("debugger.test.classpath", sourceSets["test"].runtimeClasspath.asPath) }
    systemProperty("debugger.test.examples", providers.gradleProperty("debuggerTestExamples").orElse("false").get())
    systemProperty("debugger.test.examplesRoot", rootProject.layout.projectDirectory.dir("examples").asFile.absolutePath)
    systemProperty("debugger.test.jdk", System.getProperty("java.home"))
    systemProperty("debugger.test.jcef", providers.gradleProperty("debuggerTestJcef").orElse("false").get())
    systemProperty("debugger.test.ide", providers.gradleProperty("debuggerTestIde").orElse("").get())
    systemProperty("debugger.test.distribution", providers.gradleProperty("debuggerTestDistribution").orElse("").get())
    systemProperty("debugger.test.hostBuild", rootProject.project(":jtools").layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("debugger.test.jar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
    systemProperty("debugger.test.output", layout.buildDirectory.dir("acceptance").get().asFile.absolutePath)
}
