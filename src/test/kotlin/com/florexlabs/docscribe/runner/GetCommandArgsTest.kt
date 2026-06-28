package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetCommandArgsTest {
    @Test
    fun `check mode with defaults returns empty args`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = false,
            )
        assertTrue(args.isEmpty())
    }

    @Test
    fun `check mode with json adds format flag`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
            )
        assertEquals(listOf("--format", "json"), args)
    }

    @Test
    fun `check mode with json and file path adds both`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
                filePath = "src/app.rb",
            )
        assertEquals(listOf("--format", "json", "src/app.rb"), args)
    }

    @Test
    fun `safe mode adds a and B flags`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.SAFE,
                formatJson = true,
            )
        assertEquals(listOf("-a", "-B"), args)
    }

    @Test
    fun `safe mode ignores json flag`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.SAFE,
                formatJson = true,
                filePath = "foo.rb",
            )
        assertEquals(listOf("-a", "-B", "foo.rb"), args)
    }

    @Test
    fun `aggressive mode adds A k and B flags`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.AGGRESSIVE,
                formatJson = false,
            )
        assertEquals(listOf("-A", "-k", "-B"), args)
    }

    @Test
    fun `aggressive mode with file path`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.AGGRESSIVE,
                formatJson = true,
                filePath = "lib/foo.rb",
            )
        assertEquals(listOf("-A", "-k", "-B", "lib/foo.rb"), args)
    }
}
