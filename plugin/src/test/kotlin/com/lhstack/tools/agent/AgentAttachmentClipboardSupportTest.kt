package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentAttachmentClipboardSupportTest {

    @Test
    fun `uri list text resolves files`() {
        val files = AgentAttachmentClipboardSupport.parseFiles(
            "file:///C:/Users/test/Pictures/demo.png\r\nfile:///D:/docs/readme.md\r\n"
        )

        assertEquals(2, files.size)
        assertTrue(files[0].path.contains("demo.png"))
        assertTrue(files[1].path.contains("readme.md"))
    }

    @Test
    fun `windows file path lines resolve files`() {
        val files = AgentAttachmentClipboardSupport.parseFiles(
            "C:\\Users\\test\\Desktop\\image.png\nD:\\work\\docs\\report.pdf"
        )

        assertEquals(
            listOf(
                File("C:\\Users\\test\\Desktop\\image.png").path,
                File("D:\\work\\docs\\report.pdf").path,
            ),
            files.map { it.path }
        )
    }
}
