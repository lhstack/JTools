plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
    id("io.github.sgtsilvio.gradle.proguard") version "0.7.0"
}

group = "com.lhstack"
version = "1.0.9"

repositories {
    mavenLocal()
    maven("https://maven.aliyun.com/repository/public/")
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2022.3")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("com.intellij.java", "org.jetbrains.plugins.yaml", "org.intellij.groovy","org.jetbrains.idea.maven", "org.jetbrains.plugins.gradle.dependency.updater"))
}

dependencies {
    // https://mvnrepository.com/artifact/cn.hutool/hutool-core
    implementation("cn.hutool:hutool-core:5.8.37")
    implementation(project(":sdk"))
}

val proguardJar by tasks.registering(proguard.taskClass) {
//    addInput {
//        classpath.from(tasks.shadowJar)
//    }
    addInput {
        classpath.from(base.libsDirectory.file("instrumented-${project.name}-${project.version}.jar"))
    }
    addOutput {
        archiveFile.set(base.libsDirectory.file("${project.name}-${project.version}-proguarded.jar"))
    }
    jdkModules.add("java.base")
    mappingFile.set(base.libsDirectory.file("${project.name}-${project.version}-mapping.txt"))

    rules.addAll(
        "-target 17",
        "-dontoptimize",
        "-useuniqueclassmembernames",
        "-dontwarn !com.lhstack.tools.**",
        "-flattenpackagehierarchy",
        "-libraryjars C:\\Users\\lhstack\\.m2\\repository\\org\\jetbrains\\kotlin\\kotlin-stdlib\\1.9.22\\kotlin-stdlib-1.9.22.jar",
        "-libraryjars F:\\Repo\\Gradle\\caches\\modules-2\\files-2.1\\com.jetbrains.intellij.idea\\ideaIC\\2022.3\\4d343cadac04a0a31d70f6f96facfaa7f949df01\\ideaIC-2022.3\\lib\\util.jar",
        "-libraryjars F:\\Repo\\Gradle\\caches\\modules-2\\files-2.1\\com.jetbrains.intellij.idea\\ideaIC\\2022.3\\4d343cadac04a0a31d70f6f96facfaa7f949df01\\ideaIC-2022.3\\lib\\app.jar",
        "-libraryjars D:\\Program Files\\java\\17/jmods/java.base.jmod(!.jar;!module-info.class)",
        "-libraryjars D:\\Program Files\\java\\17/jmods/java.desktop.jmod(!.jar;!module-info.class)",
        "-keep class com.lhstack.tools.listener.PluginProjectManagerListener { *; }",
        "-keep class com.lhstack.tools.listener.PluginAppLifecycleListener { *; }",
        "-keep class com.lhstack.tools.listener.JavaPluginAppLifecycleListener { *; }",
        "-keep class com.lhstack.tools.listener.ProjectStartupActivity { *; }",
        "-keep class com.lhstack.tools.plugins.PluginState** { *; }",
        "-keep class com.lhstack.tools.plugins.PluginInfo** { *; }",
        "-keep class com.lhstack.tools.plugins.CefPluginInfo** { *; }",
        "-keep class com.lhstack.tools.plugins.CefQueryCommand** { *; }",
        "-keep class com.lhstack.tools.plugins.CefPluginCacheState** { *; }",
//        "-keep class com.lhstack.tools.actions.DeveloperPageAction { *; }",
        "-keep class com.lhstack.tools.plugins.CefPluginCacheState\$State** { *; }",
        "-keep class com.lhstack.tools.plugins.PluginState\$State** { *; }",
        "-keep class com.lhstack.tools.actions.DeveloperState\$State** { *; }",
        "-keepattributes Signature,InnerClasses,*Annotation*",
        //不需要混淆类名,但是需要混淆里面的函数
//        "-keepnames class com.lhstack.tools.plugins.PluginManager",
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
        kotlinOptions.jvmTarget = "17"
        kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=all")
    }

    patchPluginXml {
        sinceBuild.set("223")
        untilBuild.set("251.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
