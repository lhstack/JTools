package com.lhstack.tools.const

class Const {

    companion object{
        const val TOOLS_WINDOW_ID = "Tools"

        const val JTOOLS_SDK_NAME = "JTools:Sdk"

        val JTOOLS_PLUGIN_HOME = "${System.getProperty("user.home")}/.ideaTools".replace("\\", "/")

        val JTOOLS_SDK_INSTALL_PATH = "${JTOOLS_PLUGIN_HOME}/sdk/sdk.jar"

        val JTOOLS_SDK_GRADLE_GROOVY_DSL = "implementation(files(\"${JTOOLS_SDK_INSTALL_PATH}\"))"
    }
}