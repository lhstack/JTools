package com.lhstack.tools.agent.model.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse

class IdeProjectTextSearchThreadingTest {
    @Test
    fun `FindInProjectUtil is not invoked inside smartRead`() {
        val source = Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/IdeProjectSupport.kt"),
        )
        val searchText = source.substringAfter("fun searchText(").substringBefore("private fun appendIndexedUsage")

        assertFalse(searchText.contains("smartRead {"))
    }
    @Test
    fun `path glob is applied to returned project paths rather than FindModel file names`() {
        val source = Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/IdeProjectSupport.kt"),
        )
        val searchText = source.substringAfter("fun searchText(").substringBefore("private fun appendIndexedUsage")

        assertFalse(searchText.contains("fileFilter = filePattern"))
        kotlin.test.assertTrue(searchText.contains("ProjectFileGlob(filePattern)"))
    }

}
