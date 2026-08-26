package com.florexlabs.docscribe.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocScribeMcpToolsetTest {
    @Test
    fun testToolsetCanBeInstantiated() {
        val toolset = DocScribeMcpToolset()
        assertNotNull(toolset)
    }

    @Test
    fun testCheckFileWithNoProjectReturnsErrorQuickly() = runBlocking {
        val toolset = DocScribeMcpToolset()
        // No open project in plain unit test, so findProject will return null and it should return error quickly without hanging
        val result = toolset.docscribe_check_file(
            filePath = "/tmp/nonexistent.rb",
            projectPath = "/tmp/does-not-exist-xyz-12345"
        )
        assertNotNull(result)
        // Should return error result with exitCode 2 when no project
        assertTrue(result.exitCode == 2 || !result.success)
    }

    @Test
    fun testDoctorWithNoProjectReturnsErrorQuickly() = runBlocking {
        val toolset = DocScribeMcpToolset()
        val result = toolset.docscribe_doctor(projectPath = "/tmp/does-not-exist-xyz-12345")
        assertNotNull(result)
        assertNotNull(result.report)
        // Should contain error about no project
        assertTrue(result.report.contains("No open project") || result.report.contains("Project root:"))
    }
}
