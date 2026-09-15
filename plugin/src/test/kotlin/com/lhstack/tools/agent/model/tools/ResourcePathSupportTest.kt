package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcePathSupportTest {
    @Test
    fun `only protocol URLs use VFS resolution`() {
        assertTrue(ResourcePathSupport.isProtocolPath("file:///tmp/image.png"))
        assertTrue(ResourcePathSupport.isProtocolPath("jar:///tmp/library.jar!/image.png"))
        assertTrue(ResourcePathSupport.isProtocolPath("jrt://java.base/java/lang/String.class"))
        assertFalse(ResourcePathSupport.isProtocolPath("../image.png"))
        assertFalse(ResourcePathSupport.isProtocolPath("/tmp/image.png"))
        assertFalse(ResourcePathSupport.isProtocolPath("~/image.png"))
    }
}
