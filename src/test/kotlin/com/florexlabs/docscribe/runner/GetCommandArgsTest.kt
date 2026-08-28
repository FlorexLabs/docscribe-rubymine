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

    @Test
    fun `check mode with rbs adds rbs flag`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
                filePath = "a.rb",
                useRbs = true,
                useRbsCollection = false,
            )
        assertEquals(listOf("--rbs", "--format", "json", "a.rb"), args)
    }

    @Test
    fun `check mode with rbs collection adds both flags`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = false,
                filePath = null,
                useRbs = true,
                useRbsCollection = true,
            )
        assertEquals(listOf("--rbs", "--rbs-collection"), args)
    }

    @Test
    fun `safe mode with rbs uses aggressive flags for RBS types`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.SAFE,
                formatJson = false,
                filePath = "foo.rb",
                useRbs = true,
                useRbsCollection = false,
            )
        assertEquals(listOf("-A", "-k", "-B", "--rbs", "foo.rb"), args)
    }

    @Test
    fun `check mode with validate_types adds flag`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
                filePath = "a.rb",
                useRbs = false,
                useRbsCollection = false,
                validateTypes = true,
            )
        assertEquals(listOf("--validate-types", "--format", "json", "a.rb"), args)
    }

    @Test
    fun `check mode with rbs and validate_types adds both`() {
        val args =
            DocscribeRunner.getCommandArgs(
                strategy = DocscribeStrategy.CHECK,
                formatJson = false,
                filePath = null,
                useRbs = true,
                useRbsCollection = false,
                validateTypes = true,
            )
        assertEquals(listOf("--rbs", "--validate-types"), args)
    }
}
