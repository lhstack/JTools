import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("io.github.sgtsilvio.gradle.proguard") version "0.7.0"
}

group = "com.lhstack"
version = "1.1.2.5"
evaluationDependsOn(":sdk")
repositories {
    intellijPlatform {
        defaultRepositories()
    }
    mavenLocal()
    maven("https://maven.aliyun.com/repository/public/")
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/cn.hutool/hutool-core
    implementation("cn.hutool:hutool-core:5.8.37")
    implementation(project(":sdk"))
    intellijPlatform{
        intellijIdeaCommunity("2025.2")
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
val proguardRules = listOf(
    "-target 17",
    "-dontoptimize",
    "-useuniqueclassmembernames",
    "-dontwarn !com.lhstack.tools.**",
    "-flattenpackagehierarchy",
    "-libraryjars /Volumes/Documents/repo/gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar",
    "-libraryjars /Volumes/Documents/repo/gradle/caches/transforms-4/b7d39ba7ebfe4e6e47f9dff8282428be/transformed/ideaIC-2025.2-aarch64/lib/util.jar",
    "-libraryjars /Volumes/Documents/repo/gradle/caches/transforms-4/b7d39ba7ebfe4e6e47f9dff8282428be/transformed/ideaIC-2025.2-aarch64/lib/app.jar",
    "-libraryjars /Volumes/Documents/repo/gradle/caches/transforms-4/b7d39ba7ebfe4e6e47f9dff8282428be/transformed/ideaIC-2025.2-aarch64/lib/app-client.jar",
    "-libraryjars /Users/lhstack/.sdkman/candidates/java/17.0.9-graalce/jmods/java.base.jmod(!.jar;!module-info.class)",
    "-libraryjars /Users/lhstack/.sdkman/candidates/java/17.0.9-graalce/jmods/java.desktop.jmod(!.jar;!module-info.class)",
    "-keep class com.lhstack.tools.listener.PluginProjectManagerListener { *; }",
    "-keep class com.lhstack.tools.listener.PluginAppLifecycleListener { *; }",
    "-keep class com.lhstack.tools.listener.JavaPluginAppLifecycleListener { *; }",
    "-keep class com.lhstack.tools.listener.ProjectStartupActivity { *; }",
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
        -keepclassmember class com.lhstack.tools.actions.** {
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
        
        -keepclassmember class com.lhstack.tools.plugins.** {
            public *;
            protected *;
        }
        
        -keepclassmember class com.lhstack.tools.listener.** {
            public *;
            protected *;
        }
        
        -keepclassmember class com.lhstack.tools.ext.** {
            public *;
            protected *;
        }
        
        -keepclassmember class com.lhstack.tools.const.** {
            public *;
            protected *;
        }
       
       -keepclassmember class com.intellij.util.lang.ClassPath** {
            public *;
            protected *;
        }
       
        -keep interface kotlin.jvm.functions.Function*
        
        -keep class kotlin.jvm.functions.Function*
        
        -keep class com.intellij.util.lang.ClassPath
            
        -keepclassmember class com.lhstack.tools.components.** {
            public *;
            protected *;
        }
        
        -keepclassmember class com.lhstack.tools.converter.** {
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
    dependsOn(instrumentedJar)
    addInput {
        classpath.from(instrumentedJar.flatMap { it.archiveFile })
    }
    addOutput {
        archiveFile.set(base.libsDirectory.file("${project.name}-${project.version}-proguarded.jar"))
    }
    jdkModules.add("java.base")
    mappingFile.set(base.libsDirectory.file("${project.name}-${project.version}-mapping.txt"))

    rules.addAll(proguardRules)
}

val proguardSdkJar by tasks.registering(proguard.taskClass) {
    dependsOn(sdkJar)
    addInput {
        classpath.from(sdkJar.flatMap { it.archiveFile })
    }
    addOutput {
        archiveFile.set(base.libsDirectory.file("sdk-${sdkVersion}-proguarded.jar"))
    }
    jdkModules.add("java.base")
    mappingFile.set(base.libsDirectory.file("sdk-${sdkVersion}-mapping.txt"))
    rules.addAll(proguardRules)
}

val applyObfuscationOutputs by tasks.registering {
    dependsOn(proguardJar, proguardSdkJar)
    doLast {
        val libsDir = base.libsDirectory.get().asFile
        val pluginProguarded = File(libsDir, "${project.name}-${project.version}-proguarded.jar")
        val pluginJar = File(libsDir, "${project.name}-${project.version}.jar")
        val pluginInstrumentedJar = File(libsDir, "${project.name}-${project.version}-instrumented.jar")
        pluginProguarded.copyTo(pluginJar, overwrite = true)
        pluginProguarded.copyTo(pluginInstrumentedJar, overwrite = true)

        val sdkProguarded = File(libsDir, "sdk-${sdkVersion}-proguarded.jar")
        val sdkJarFile = sdkJar.get().archiveFile.get().asFile
        sdkProguarded.copyTo(sdkJarFile, overwrite = true)
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
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
        sinceBuild.set("251")
        untilBuild.set("261.*")
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
    named("buildPlugin") {
        dependsOn(applyObfuscationOutputs)
    }
    named("publishPlugin") {
        dependsOn(applyObfuscationOutputs)
    }
}
