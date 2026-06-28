package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DocscribeRunnerTest {
    private class RecordingExecutor : CommandExecutor {
        var lastCommand: String? = null
        var lastArgs: List<String>? = null
        var lastCwd: String? = null

        override fun execute(
            cmd: String,
            args: List<String>,
            cwd: String,
        ): RunResult {
            lastCommand = cmd
            lastArgs = args
            lastCwd = cwd
            return RunResult(success = true, hasIssues = false, exitCode = 0, stdout = "", stderr = "")
        }
    }

    @Test
    fun `runDocscribe with check strategy uses bundle exec docscribe with json format`() {
        val executor = RecordingExecutor()
        DocscribeRunner.runDocscribe(
            RunOptions(projectDir = "/project", file = "test.rb", strategy = DocscribeStrategy.CHECK, formatJson = true),
            executor,
        )
        assertEquals("bundle", executor.lastCommand)
        assertEquals(listOf("exec", "docscribe", "--format", "json", "test.rb"), executor.lastArgs)
        assertEquals("/project", executor.lastCwd)
    }

    @Test
    fun `runDocscribe with safe strategy uses bundle exec docscribe -a`() {
        val executor = RecordingExecutor()
        DocscribeRunner.runDocscribe(
            RunOptions(projectDir = "/project", file = "test.rb", strategy = DocscribeStrategy.SAFE),
            executor,
        )
        assertEquals("bundle", executor.lastCommand)
        assertEquals(listOf("exec", "docscribe", "-a", "test.rb"), executor.lastArgs)
    }

    @Test
    fun `runDocscribe with aggressive strategy uses bundle exec docscribe -A -k`() {
        val executor = RecordingExecutor()
        DocscribeRunner.runDocscribe(
            RunOptions(projectDir = "/project", file = "test.rb", strategy = DocscribeStrategy.AGGRESSIVE),
            executor,
        )
        assertEquals("bundle", executor.lastCommand)
        assertEquals(listOf("exec", "docscribe", "-A", "-k", "test.rb"), executor.lastArgs)
    }

    @Test
    fun `runDocscribe with subcommand delegates directly`() {
        val executor = RecordingExecutor()
        DocscribeRunner.runDocscribe(
            RunOptions(projectDir = "/project", subcommand = "update_types"),
            executor,
        )
        assertEquals("bundle", executor.lastCommand)
        assertEquals(listOf("exec", "docscribe", "update_types", "/project"), executor.lastArgs)
    }

    @Test
    fun `runDocscribe with no file uses no file arg`() {
        val executor = RecordingExecutor()
        DocscribeRunner.runDocscribe(
            RunOptions(projectDir = "/project", strategy = DocscribeStrategy.CHECK, formatJson = true),
            executor,
        )
        assertEquals(listOf("exec", "docscribe", "--format", "json"), executor.lastArgs)
    }

    @Test
    fun `runDocscribe defaults to DefaultCommandExecutor does not throw`() {
        val tempDir = createTempDirectory().toFile()
        try {
            File(tempDir, "Gemfile").writeText(
                """
                source "https://rubygems.org"
                gem "docscribe"
                """.trimIndent(),
            )
            DocscribeRunner.runDocscribe(
                RunOptions(projectDir = tempDir.absolutePath, strategy = DocscribeStrategy.CHECK),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
