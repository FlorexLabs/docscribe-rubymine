package com.florexlabs.docscribe.mcp

import org.junit.Assert.assertNotNull
import org.junit.Test

class DocScribeMcpToolsetTest {
    @Test
    fun testToolsetCanBeInstantiated() {
        val toolset = DocScribeMcpToolset()
        assertNotNull(toolset)
    }
}
