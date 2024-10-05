package com.lhstack.tools.const

import com.intellij.build.BuildTextConsoleView
import com.intellij.openapi.util.Key

class Const {

    companion object {
        const val TOOLS_WINDOW_ID = "JTools"

        val JTOOLS_PLUGIN_HOME = "${System.getProperty("user.home")}/.jtools".replace("\\", "/")

        val JTOOLS_SDK_INSTALL_PATH = "${JTOOLS_PLUGIN_HOME}/sdk/sdk.jar"

        val JTOOLS_SDK_MAVEN_GROUP_ID = "com.lhstack.plugins.jtools"

        val JTOOLS_SDK_MAVEN_ARTIFACT_ID = "JTools-Sdk"

        val JTOOLS_SDK_MAVEN_VERSION = "0.0.1"

        val JTOOLS_SDK_IDEA_PROJECT_LIBRARY = "JTools: Sdk"

        val LOG_CONSOLE_KEY = Key.create<BuildTextConsoleView>("JTOOLS_LOG_CONSOLE_KEY")
    }
}