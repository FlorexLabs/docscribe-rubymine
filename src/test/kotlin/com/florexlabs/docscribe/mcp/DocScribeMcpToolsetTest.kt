package com.florexlabs.docscribe.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

@Suppress("MaxLineLength")
class DocScribeMcpToolsetTest : BasePlatformTestCase() {
    private lateinit var toolset: DocScribeMcpToolset

    override fun setUp() {
        super.setUp()
        toolset = DocScribeMcpToolset()
    }

    fun testToolsetCanBeInstantiated() {
        assertNotNull(toolset)
    }

    fun testDoctorWithCurrentProjectReturnsReport() =
        runBlocking {
            val projectPath = project.basePath
            assertNotNull("project basePath should not be null in test", projectPath)
            val result = toolset.docscribe_doctor(projectPath = projectPath)
            assertNotNull(result)
            assertNotNull(result.report)
            assertTrue("Doctor report should contain header", result.report.contains("DocScribe Diagnostics"))
            assertTrue("Doctor report should contain project root", result.report.contains("Project root:"))
        }

    fun testCheckWorkspaceReturnsQuicklyEvenWithNoFiles() =
        runBlocking {
            withTimeout(5.seconds) {
                val projectPath = project.basePath
                assertNotNull(projectPath)
                // This should not hang - it will return quickly even if daemon is not available
                // We just check that it doesn't throw and returns a result within timeout
                val result = toolset.docscribe_check_workspace(projectPath = projectPath)
                assertNotNull(result)
                assertNotNull(result.projectPath)
            }
        }

    fun testSafeFixHandlesMissingProjectGracefully() =
        runBlocking {
            withTimeout(5.seconds) {
                // Use a definitely non-existent project path, but BasePlatformTestCase always has one open project
                // so it will fallback to that project and then try to run daemon - we just ensure it doesn't hang
                // and returns a result (even if it's an error)
                val result =
                    toolset.docscribe_safe_fix(
                        filePath = "/tmp/does-not-exist-xyz-12345.rb",
                        projectPath = project.basePath,
                    )
                assertNotNull(result)
                assertTrue(result.exitCode >= 0)
            }
        }
}
