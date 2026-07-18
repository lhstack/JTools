package com.lhstack.tools.agent.model.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectReadBatchContractTest {
    @Test
    fun `read batch permits one path with distinct range requests`() {
        val support = source("IdeProjectSupport.kt")
        val definitions = source("IdeProjectTools.kt")
        assertFalse(support.contains("read paths must not contain duplicates"))
        assertTrue(definitions.contains("同一路径可使用不同范围重复读取"))
    }

    @Test
    fun `replace batch permits multiple non-overlapping edits per path`() {
        val support = source("IdeProjectSupport.kt")
        val definitions = source("IdeProjectTools.kt")
        assertFalse(support.contains("replace batch supports at most one edit per file"))
        assertTrue(definitions.contains("同一路径可包含多个修改"))
    }

    private fun source(file: String): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/$file"),
    )
}
