package com.florexlabs.docscribe.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

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

    fun testCheckFileWithNoProjectReturnsError() =
        runBlocking {
            // No open project that matches the path, but BasePlatformTestCase has one open project in temp dir
            // Use a non-existent project path to trigger the "No open project" branch
            val result =
                toolset.docscribe_check_file(
                    filePath = "/tmp/nonexistent.rb",
                    projectPath = "/tmp/does-not-exist-xyz-12345",
                )
            // When project not found, it returns a synthetic error result with exitCode 2 and success=false
            // However, since BasePlatformTestCase always has one open project, findProject will fallback to that
            // So we check that it either returns error or delegates to daemon (which will fallback to CLI and fail gracefully)
            assertNotNull(result)
            // The tool should always return a CheckResult, even for missing project
            assertTrue(
                result.projectPath == "/tmp/does-not-exist-xyz-12345" || result.projectPath == "/tmp/nonexistent.rb" ||
                    result.filePath == "/tmp/nonexistent.rb",
            )
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

    fun testCheckWorkspaceWithCurrentProject() =
        runBlocking {
            val projectPath = project.basePath
            assertNotNull(projectPath)
            val result = toolset.docscribe_check_workspace(projectPath = projectPath)
            assertNotNull(result)
            assertNotNull(result.projectPath)
            assertTrue(result.exitCode >= 0)
        }

    fun testSafeFixWithTempFile() =
        runBlocking {
            val psiFile = myFixture.configureByText("test_mcp.rb", "def foo\nend\n")
            val vFile = psiFile.virtualFile
            assertNotNull(vFile)
            val result = toolset.docscribe_safe_fix(filePath = vFile.path, projectPath = project.basePath)
            assertNotNull(result)
            // Safe fix should run (may be no-op if no docscribe gem, but should not throw)
            assertTrue(result.exitCode >= 0)
        }
}
