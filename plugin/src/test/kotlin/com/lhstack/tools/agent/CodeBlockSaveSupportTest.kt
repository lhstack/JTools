package com.lhstack.tools.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeBlockSaveSupportTest {
    @Test
    fun `windows path leaf is used`() {
        assertEquals("Main.kt", CodeBlockSaveSupport.suggestedFileName("C:\\Users\\a\\Main.kt"))
    }

    @Test
    fun `unix path leaf is used`() {
        assertEquals("code.py", CodeBlockSaveSupport.suggestedFileName("/tmp/code.py"))
    }

    @Test
    fun `illegal characters are replaced`() {
        assertEquals("a_b_c.txt", CodeBlockSaveSupport.suggestedFileName("a:b*c.txt"))
    }

    @Test
    fun `windows reserved names are renamed`() {
        assertEquals("CON_file.txt", CodeBlockSaveSupport.suggestedFileName("CON.txt"))
    }

    @Test
    fun `blank name falls back`() {
        assertEquals("code.txt", CodeBlockSaveSupport.suggestedFileName("   "))
    }
}
