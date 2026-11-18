import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("io.github.sgtsilvio.gradle.proguard") version "0.7.0"
    id("com.gradleup.shadow") version "8.3.6" apply false
}

group = "com.lhstack"
version = "1.1.6.0"
evaluationDependsOn(":sdk")
repositories {
    intellijPlatform {
        defaultRepositories()
    }
    mavenLocal()
    mavenCentral()
}

configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-nop")
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
    exclude(group = "org.slf4j", module = "slf4j-reload4j")
}

intellijPlatform {
    // This plugin has no applicationConfigurable/projectConfigurable extensions.
    // Running traverseUI generates no plugin-owned settings index and may collide with
    // an active runIde instance that uses the same sandbox.
    buildSearchableOptions.set(false)
}

val svgRenderer by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
// IDE 也会捆绑 JSVG；编译、测试和发布统一使用独立命名空间的这一份。
val svgRendererJar by tasks.registering(ShadowJar::class) {
    configurations = listOf(svgRenderer)
    archiveBaseName.set("jtools-svg-renderer")
    archiveClassifier.set("")
    relocate("com.github.weisj.jsvg", "com.lhstack.tools.vendor.jsvg")
}

dependencies {
    svgRenderer("com.github.weisj:jsvg:2.2.0")
    implementation(files(svgRendererJar.flatMap { it.archiveFile }))
    // https://mvnrepository.com/artifact/cn.hutool/hutool-core
    implementation("cn.hutool:hutool-core:5.8.37")
    // sqlite + mybatis-plus + hikaricp (persistence)
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("com.baomidou:mybatis-plus:3.5.3.1")
    implementation("org.springframework:spring-core:5.3.39")
    implementation("org.springframework:spring-jdbc:5.3.39")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dnsjava:dnsjava:3.6.5")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.htmlunit:htmlunit:4.21.0")
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.3")
    implementation("org.commonmark:commonmark:0.28.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.28.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.28.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.28.0")
    implementation(project(":sdk"))
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("junit:junit:4.13.2")
    intellijPlatform{
        val localPlatform = providers.gradleProperty("intellijPlatformLocalPath").orNull
        if (localPlatform != null) local(localPlatform)
        else intellijIdeaCommunity("2025.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("org.jetbrains.idea.gradle.dsl")
    }

}
val sdkProject = project(":sdk")
val sdkVersion = sdkProject.version.toString()
val instrumentedJar = tasks.named<Jar>("instrumentedJar")
val sdkJar = sdkProject.tasks.named<Jar>("jar")
// SDK 使用原始构建产物交付，不创建 SDK ProGuard 任务。
val sdkComposedJar = sdkProject.tasks.named<Jar>("composedJar")
val proguardRules = listOf(
    "-target 17",
    "-dontoptimize",
    "-useuniqueclassmembernames",
    "-dontwarn !com.lhstack.tools.**",
    "-flattenpackagehierarchy",
    "-libraryjars /Users/lhstack/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar",
    "-libraryjars /Users/lhstack/.gradle/caches/transforms-4/b7d39ba7ebfe4e6e47f9dff8282428be/transformed/ideaIC-2025.2-aarch64/lib/util.jar",
    "-libraryjars /Users/lhstack/.gradle/caches/transforms-4/b7d39ba7ebfe4e6e47f9dff8282428be/transformed/ideaIC-2025.2-aarch64/lib/app.jar",
    "-libraryjars /Users/lhstack/.gradle/caches/transforms-4/b7d39ba7ebfe4e6e47f9dff8282428be/transformed/ideaIC-2025.2-aarch64/lib/app-client.jar",
    "-libraryjars /Users/lhstack/.sdkman/candidates/java/17.0.9-graalce/jmods/java.base.jmod(!.jar;!module-info.class)",
    "-libraryjars /Users/lhstack/.sdkman/candidates/java/17.0.9-graalce/jmods/java.desktop.jmod(!.jar;!module-info.class)",
//    "-libraryjars D:\\Documents\\Repo\\gradle\\caches\\modules-2\\files-2.1\\org.jetbrains.kotlin\\kotlin-stdlib\\2.2.10\\30de6faa127a4a012db8e71bf1b9c0a99b1402b2\\kotlin-stdlib-2.2.10.jar",
//    "-libraryjars D:\\Documents\\Repo\\gradle\\caches\\transforms-4\\43e49764f4425869ca539e4dca81a351\\transformed\\ideaIC-2025.2-win\\lib\\util.jar",
//    "-libraryjars D:\\Documents\\Repo\\gradle\\caches\\transforms-4\\43e49764f4425869ca539e4dca81a351\\transformed\\ideaIC-2025.2-win\\lib\\app.jar",
//    "-libraryjars D:\\Documents\\Repo\\gradle\\caches\\transforms-4\\43e49764f4425869ca539e4dca81a351\\transformed\\ideaIC-2025.2-win\\lib\\app-client.jar",
//    "-libraryjars D:\\Program Files\\java\\17\\jmods\\java.base.jmod(!.jar;!module-info.class)",
//    "-libraryjars D:\\Program Files\\java\\17\\jmods\\java.desktop.jmod(!.jar;!module-info.class)",
    "-keep class com.lhstack.tools.listener.PluginProjectManagerListener { *; }",
    "-keep class com.lhstack.tools.listener.PluginAppLifecycleListener { *; }",
    "-keep class com.lhstack.tools.listener.JavaPluginAppLifecycleListener { *; }",
    "-keep class com.lhstack.tools.listener.ProjectStartupActivity { *; }",
    "-keep class com.lhstack.tools.sandbox.SandboxPluginHost { public java.lang.String execute(java.lang.String,java.lang.String); public javax.swing.JComponent panel(java.lang.String); public java.lang.Object browser(java.lang.String); }",
    "-keepclassmembers class * implements com.lhstack.tools.plugins.Logger { public *; }",
    "-keepclassmembers class * implements com.lhstack.tools.plugins.CefCacheManager { public *; }",
    "-keep class com.lhstack.tools.plugins.PluginState { *; }",
    "-keep class com.lhstack.tools.db.entity.** { *; }",
    "-keep class com.lhstack.tools.db.mapper.** { *; }",
    "-keep class * implements com.baomidou.mybatisplus.core.handlers.MetaObjectHandler { *; }",
    "-keep interface com.baomidou.mybatisplus.core.handlers.MetaObjectHandler { *; }",
    "-keep class com.lhstack.tools.db.AgentMetaObjectHandler { *; }",
    "-keep class com.lhstack.tools.plugins.PluginState\$State { *; }",
    "-keep class com.lhstack.tools.actions.DeveloperState { *; }",
    "-keep class com.lhstack.tools.actions.DeveloperState\$State { *; }",
    "-keep class com.lhstack.tools.plugins.CefPluginCacheState { *; }",
    "-keep class com.lhstack.tools.plugins.CefPluginCacheState\$State { *; }",
    "-keep class com.lhstack.tools.agent.AgentAttachmentState { *; }",
    "-keep class com.lhstack.tools.agent.AgentBrowserCommand { *; }",
    "-keep class com.lhstack.tools.ide.EditorNavigation { *; }",
    "-keep class com.lhstack.tools.plugins.JcefDownloadHandlers { *; }",
    "-keep class com.lhstack.tools.agent.AgentChatPanel { *; }",
    "-keep class com.lhstack.tools.agent.AgentChatPanel$* { *; }",
    "-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }",
    "-keepclassmembers class * implements com.intellij.openapi.Disposable { public void dispose(); }",
    "-keepclassmembers class * { void dispose(); }",
    "-keepclassmembers class com.lhstack.tools.plugins.PluginState** { *; }",
    "-keepclassmembers class com.lhstack.tools.plugins.PluginInfo** { *; }",
    "-keepclassmembers class com.lhstack.tools.plugins.CefPluginInfo** { *; }",
    "-keepclassmembers class com.lhstack.tools.plugins.CefQueryCommand** { *; }",
    "-keepclassmembers class com.lhstack.tools.plugins.CefPluginCacheState** { *; }",
    "-keepclassmembers class com.lhstack.tools.plugins.CefPluginCacheState\$State** { *; }",
    "-keepclassmembers class com.lhstack.tools.plugins.PluginState\$State** { *; }",
    "-keepclassmembers class com.lhstack.tools.actions.DeveloperState\$State** { *; }",
    "-keepattributes Signature,InnerClasses,*Annotation*",
    """
        -keepclassmembers class com.lhstack.tools.actions.** {
            public *;
            protected *;
        }
        
        -keepclassmembers class cn.hutool.core.**{
            public *;
            protected *;
            private *;
        }
        
        -keep class * implements com.intellij.openapi.wm.ToolWindowFactory {
            *;
        }
        
        -keepclassmembers class com.lhstack.tools.plugins.** {
            public *;
            protected *;
        }
        
        -keepclassmembers class com.lhstack.tools.listener.** {
            public *;
            protected *;
        }
        
        -keepclassmembers class com.lhstack.tools.ext.** {
            public *;
            protected *;
        }
        
        -keepclassmembers class com.lhstack.tools.const.** {
            public *;
            protected *;
        }

        -keepclassmembers class com.lhstack.tools.agent.** {
            public *;
            protected *;
        }

        -keepclassmembers class com.lhstack.tools.dev.** {
            public *;
            protected *;
        }
       
       -keepclassmembers class com.intellij.util.lang.ClassPath** {
            public *;
            protected *;
        }
       
        -keep interface kotlin.jvm.functions.Function*
        
        -keep class kotlin.jvm.functions.Function*
        
        -keep class com.intellij.util.lang.ClassPath
            
        -keepclassmembers class com.lhstack.tools.components.** {
            public *;
            protected *;
        }
        
        -keepclassmembers class com.lhstack.tools.converter.** {
            public *;
            protected *;
        }
    """.trimIndent(),
    "-ignorewarnings"
)
val proguardJar by tasks.registering(proguard.taskClass) {
//    addInput {
//        classpath.from(tasks.shadowJar)
//    }
    dependsOn(instrumentedJar, sdkJar)
    addInput {
        classpath.from(instrumentedJar.flatMap { it.archiveFile })
    }
    // SDK 全部类与成员保持公开名称；仅作只读库输入解析接口，不参与混淆、裁剪或输出。
    addLibrary {
        classpath.from(sdkJar.flatMap { it.archiveFile })
    }
    addLibrary {
        classpath.from(svgRendererJar.flatMap { it.archiveFile })
    }
    addOutput {
        archiveFile.set(base.libsDirectory.file("${project.name}-${project.version}-proguarded.jar"))
    }
    jdkModules.add("java.base")
    mappingFile.set(base.libsDirectory.file("${project.name}-${project.version}-mapping.txt"))

    rules.addAll(proguardRules)
}

val verifySandboxHost by tasks.registering {
    dependsOn(proguardJar, sdkJar)
    val artifact = base.libsDirectory.file("${project.name}-${project.version}-proguarded.jar")
    val mapping = base.libsDirectory.file("${project.name}-${project.version}-mapping.txt")
    inputs.file(artifact)
    inputs.file(mapping)
    inputs.file(sdkJar.flatMap { it.archiveFile })
    doLast {
        fun inspect(jar: File, className: String): String {
            val output = ByteArrayOutputStream()
            exec {
                commandLine(
                    File(System.getProperty("java.home"), "bin/javap").absolutePath,
                    "-p", "-s", "-classpath", jar.absolutePath, className
                )
                standardOutput = output
            }
            return output.toString("UTF-8")
        }
        val bytecode = inspect(artifact.get().asFile, "com.lhstack.tools.sandbox.SandboxPluginHost")
        listOf(
            "public java.lang.String execute(java.lang.String, java.lang.String);" to "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            "public javax.swing.JComponent panel(java.lang.String);" to "(Ljava/lang/String;)Ljavax/swing/JComponent;",
            "public java.lang.Object browser(java.lang.String);" to "(Ljava/lang/String;)Ljava/lang/Object;"
        ).forEach { (method, descriptor) ->
            check(bytecode.contains(method) && bytecode.contains("descriptor: $descriptor")) {
                "混淆产物缺少 SandboxHost 实现：$method"
            }
        }
        val loggerSource = "com.lhstack.tools.sandbox.SandboxPluginHost\$run\$logger\$1"
        val loggerMapping = mapping.get().asFile.useLines { lines ->
            lines.single { it.startsWith("$loggerSource -> ") }.substringAfter(" -> ").removeSuffix(":")
        }
        val loggerBytecode = inspect(artifact.get().asFile, loggerMapping)
        val loggerInterface = inspect(sdkJar.get().archiveFile.get().asFile, "com.lhstack.tools.plugins.Logger")
        val abstractMethods = loggerInterface.lineSequence().map(String::trim)
            .filter { it.startsWith("public abstract ") }.toList()
        check(abstractMethods.isNotEmpty()) { "SDK Logger 没有可校验的抽象方法" }
        abstractMethods.forEach { method ->
            val implementation = method.replace("public abstract ", "public ")
            check(loggerBytecode.contains(implementation)) {
                "混淆产物缺少 Logger 实现：$implementation ($loggerMapping)"
            }
        }
    }
}

val applyObfuscationOutputs by tasks.registering {
    dependsOn(verifySandboxHost, sdkComposedJar)
    doLast {
        val libsDir = base.libsDirectory.get().asFile
        val pluginProguarded = File(libsDir, "${project.name}-${project.version}-proguarded.jar")
        val pluginJar = File(libsDir, "${project.name}-${project.version}.jar")
        val pluginInstrumentedJar = File(libsDir, "${project.name}-${project.version}-instrumented.jar")
        pluginProguarded.copyTo(pluginJar, overwrite = true)
        pluginProguarded.copyTo(pluginInstrumentedJar, overwrite = true)
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<Copy> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    withType<Zip> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
    withType<Test> {
        useJUnitPlatform()
    }
    withType<JavaExec> {
        jvmArgs("-Dfile.encoding=UTF-8")
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions{
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    patchPluginXml {
        sinceBuild.set("251.0")
        untilBuild.set("263.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    named("prepareSandbox") {
        dependsOn(applyObfuscationOutputs)
    }
    named<Zip>("buildPlugin") {
        dependsOn(applyObfuscationOutputs)
        doLast {
            fun classes(input: InputStream): Map<String, List<Byte>> =
                JarInputStream(input).use { jar ->
                    buildMap {
                        while (true) {
                            val entry = jar.nextJarEntry ?: break
                            if (!entry.isDirectory && entry.name.endsWith(".class")) {
                                put(entry.name, MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).toList())
                            }
                        }
                    }
                }
            ZipFile(archiveFile.get().asFile).use { zip ->
                val jars = zip.entries().asSequence().filter { it.name.endsWith(".jar") }.toList()
                val renderer = svgRendererJar.get().archiveFile.get().asFile
                val rendererEntry = jars.single { it.name.endsWith("/lib/${renderer.name}") }
                val rendererClasses = classes(zip.getInputStream(rendererEntry))
                check("com/lhstack/tools/vendor/jsvg/parser/SVGLoader.class" in rendererClasses &&
                    rendererClasses.keys.none { it.startsWith("com/github/weisj/jsvg/") } &&
                    rendererClasses == classes(renderer.inputStream())
                ) { "发布 ZIP 未包含正确重定位的 SVG 渲染库" }
                listOf(
                    "${project.name}-${project.version}.jar" to base.libsDirectory.file("${project.name}-${project.version}-proguarded.jar").get().asFile,
                    "sdk-$sdkVersion.jar" to sdkComposedJar.get().archiveFile.get().asFile
                ).forEach { (name, expected) ->
                    val entry = jars.single { it.name.endsWith("/lib/$name") }
                    val expectedClasses = classes(expected.inputStream())
                    check(expectedClasses.isNotEmpty() && classes(zip.getInputStream(entry)) == expectedClasses) {
                        "发布 ZIP 中的 $name 与已验证产物不一致"
                    }
                }
            }
        }
    }
    named("publishPlugin") {
        dependsOn(applyObfuscationOutputs)
    }
}
