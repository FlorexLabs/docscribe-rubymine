package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetCommandArgsTest {

    @Test
    fun `check mode with defaults returns empty args`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.CHECK,
            formatJson = false,
            useRbs = false
        )
        assertTrue(args.isEmpty())
    }

    @Test
    fun `check mode with json adds format flag`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.CHECK,
            formatJson = true,
            useRbs = false
        )
        assertEquals(listOf("--format", "json"), args)
    }

    @Test
    fun `check mode with json and rbs adds both flags`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.CHECK,
            formatJson = true,
            useRbs = true
        )
        assertEquals(listOf("--format", "json", "--rbs-collection"), args)
    }

    @Test
    fun `check mode with json, rbs and file path adds all`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.CHECK,
            formatJson = true,
            useRbs = true,
            filePath = "src/app.rb"
        )
        assertEquals(listOf("--format", "json", "--rbs-collection", "src/app.rb"), args)
    }

    @Test
    fun `safe mode adds min a flag`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.SAFE,
            formatJson = true,
            useRbs = false
        )
        assertEquals(listOf("-a"), args)
    }

    @Test
    fun `safe mode ignores json flag`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.SAFE,
            formatJson = true,
            useRbs = false,
            filePath = "foo.rb"
        )
        assertEquals(listOf("-a", "foo.rb"), args)
    }

    @Test
    fun `aggressive mode adds min A flag`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.AGGRESSIVE,
            formatJson = false,
            useRbs = false
        )
        assertEquals(listOf("-A"), args)
    }

    @Test
    fun `aggressive mode with rbs and file`() {
        val args = DocscribeRunner.getCommandArgs(
            strategy = DocscribeStrategy.AGGRESSIVE,
            formatJson = true,
            useRbs = true,
            filePath = "lib/foo.rb"
        )
        assertEquals(listOf("-A", "--rbs-collection", "lib/foo.rb"), args)
    }
}
