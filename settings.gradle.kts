pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "tools"

include(":sdk")
project(":sdk").projectDir=File("$rootDir/sdk")

include(":plugin")
project(":plugin").projectDir=File("$rootDir/plugin")
project(":plugin").name = "jtools"
include(":plugin-example")
project(":plugin-example").projectDir=File("$rootDir/plugin-example")