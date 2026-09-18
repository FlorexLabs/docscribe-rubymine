package com.florexlabs.docscribe.runner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocscribeDaemonCliOverridesTest {
    @Test
    fun `supportsCliOverrides false for gems before 1_6_2`() {
        assertFalse(DocscribeDaemon.supportsCliOverrides("1.6.1"))
        assertFalse(DocscribeDaemon.supportsCliOverrides("1.6.0"))
        assertFalse(DocscribeDaemon.supportsCliOverrides("1.5.2"))
        assertFalse(DocscribeDaemon.supportsCliOverrides("1.5.1"))
    }

    @Test
    fun `supportsCliOverrides true for 1_6_2 and newer`() {
        assertTrue(DocscribeDaemon.supportsCliOverrides("1.6.2"))
        assertTrue(DocscribeDaemon.supportsCliOverrides("1.7.0"))
        assertTrue(DocscribeDaemon.supportsCliOverrides("2.0.0"))
    }

    @Test
    fun `supportsCliOverrides true for unknown versions`() {
        assertTrue(DocscribeDaemon.supportsCliOverrides(null))
        assertTrue(DocscribeDaemon.supportsCliOverrides(""))
        assertTrue(DocscribeDaemon.supportsCliOverrides("dev"))
    }

    @Test
    fun `buildUpdateTypesParams omits cli_overrides when gated off`() {
        val dir = Files.createTempDirectory("rbs-gate-update").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "a.rbs").writeText("class A; end")
            val params = DocscribeDaemon.buildUpdateTypesParams(dir.absolutePath, includeCliOverrides = false)
            assertFalse(params.containsKey("cli_overrides"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildBatchParams omits cli_overrides when gated off`() {
        val dir = Files.createTempDirectory("rbs-gate-batch").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "b.rbs").writeText("class B; end")
            val params =
                DocscribeDaemon.buildBatchParams(
                    listOf("/tmp/a.rb"),
                    dir.absolutePath,
                    includeCliOverrides = false,
                )
            assertFalse(params.containsKey("cli_overrides"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
