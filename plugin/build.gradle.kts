plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
    id("io.github.sgtsilvio.gradle.proguard") version "0.7.0"
}

group = "com.lhstack"
version = "1.0-SNAPSHOT"

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

    plugins.set(listOf("com.intellij.java", "org.jetbrains.plugins.yaml", "org.intellij.groovy"))
}

dependencies {
    implementation(project(":sdk"))
}

val proguardJar by tasks.registering(proguard.taskClass) {
//    addInput {
//        classpath.from(tasks.shadowJar)
//    }
    addInput {
        classpath.from(base.libsDirectory.file("instrumented-plugin-1.0-SNAPSHOT.jar"))
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
        "-libraryjars F:\\Repo\\Gradle\\wrapper\\dists\\gradle-8.6-all\\6itsypff3gopqo4yna2pr643r\\gradle-8.6\\lib\\kotlin-stdlib-1.9.20.jar",
        "-libraryjars D:\\Program Files\\java\\17/jmods/java.base.jmod(!.jar;!module-info.class)",
        "-keep class com.lhstack.tools.ToolsMainWindowFactory { *; }",
        "-keep class com.lhstack.tools.listener.PluginProjectManagerListener { *; }",
        "-keep class com.lhstack.tools.listener.PluginAppLifecycleListener { *; }",
        "-keep class com.lhstack.tools.listener.ProjectStartupActivity { *; }",
        "-keep class com.lhstack.tools.plugins.PluginState** { *; }",
        "-keepattributes Signature,InnerClasses,*Annotation*",
        //不需要混淆类名,但是需要混淆里面的函数
//        "-keepnames class com.lhstack.tools.plugins.PluginManager",
        """
            -keepclassmember class com.lhstack.tools.actions.** {
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
           
            -keep interface kotlin.jvm.functions.Function*
            
            -keep class kotlin.jvm.functions.Function*
                
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
        untilBuild.set("242.*")
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
