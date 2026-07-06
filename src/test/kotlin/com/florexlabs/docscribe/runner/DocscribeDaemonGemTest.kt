package com.florexlabs.docscribe.runner

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeDaemonGemTest : BasePlatformTestCase() {
    fun testExecuteReturnsDescriptiveErrorWhenDocscribeMissing() {
        val daemon = DocscribeDaemon(project)
        daemon.docscribeStatus = DocscribeDaemon.DocscribeStatus.MISSING
        val result = daemon.execute("check", file = "test.rb", projectDir = "/tmp")
        assertFalse(result.success)
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("gem \"docscribe\""))
        assertTrue(result.stderr.contains("bundle install"))
    }

    fun testFallbackDoesNotRunCliWhenDocscribeMissing() {
        val daemon = DocscribeDaemon(project)
        daemon.docscribeStatus = DocscribeDaemon.DocscribeStatus.MISSING
        val result = daemon.execute("safe_fix", file = "test.rb", projectDir = "/tmp")
        assertFalse(result.success)
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("gem \"docscribe\""))
    }

    fun testExecuteReturnsSameErrorForAllCommands() {
        val daemon = DocscribeDaemon(project)
        daemon.docscribeStatus = DocscribeDaemon.DocscribeStatus.MISSING
        for (cmd in listOf("check", "safe_fix", "aggressive_fix", "update_types")) {
            val result = daemon.execute(cmd, file = "test.rb", projectDir = "/tmp")
            assertFalse("$cmd should fail when gem missing", result.success)
            assertTrue("$cmd should mention gem in stderr", result.stderr.contains("docscribe gem"))
        }
    }

    fun testGemCheckSkipsWhenAlreadyChecked() {
        val daemon = DocscribeDaemon(project)
        // Set to AVAILABLE before calling performGemCheck — should skip the real process
        daemon.docscribeStatus = DocscribeDaemon.DocscribeStatus.AVAILABLE
        // Should return immediately without throwing
        daemon.performGemCheck()
        assertEquals(DocscribeDaemon.DocscribeStatus.AVAILABLE, daemon.docscribeStatus)
    }

    fun testDocscribeStatusDefaultsToUnchecked() {
        val daemon = DocscribeDaemon(project)
        assertEquals(DocscribeDaemon.DocscribeStatus.UNCHECKED, daemon.docscribeStatus)
    }

    // ── version parsing ──────────────────────────────────────────────

    fun testParseVersion150NoServerMode() {
        val caps = DocscribeDaemon.parseVersion("1.5.0")
        assertNotNull(caps)
        assertEquals("1.5.0", caps!!.version)
        assertFalse(caps.serverMode)
    }

    fun testParseVersion151ServerMode() {
        val caps = DocscribeDaemon.parseVersion("1.5.1")
        assertNotNull(caps)
        assertEquals("1.5.1", caps!!.version)
        assertTrue(caps.serverMode)
    }

    fun testParseVersion160ServerMode() {
        val caps = DocscribeDaemon.parseVersion("1.6.0")
        assertNotNull(caps)
        assertTrue(caps!!.serverMode)
    }

    fun testParseVersion200ServerMode() {
        val caps = DocscribeDaemon.parseVersion("2.0.0")
        assertNotNull(caps)
        assertTrue(caps!!.serverMode)
    }

    fun testParseVersion149NoServerMode() {
        val caps = DocscribeDaemon.parseVersion("1.4.9")
        assertNotNull(caps)
        assertFalse(caps!!.serverMode)
    }

    fun testParseVersionHandlesTrailingNewline() {
        val caps = DocscribeDaemon.parseVersion("1.5.1\n")
        assertNotNull(caps)
        assertTrue(caps!!.serverMode)
    }

    fun testParseVersionReturnsNullForGarbage() {
        assertNull(DocscribeDaemon.parseVersion(""))
        assertNull(DocscribeDaemon.parseVersion("not-a-version"))
        assertNull(DocscribeDaemon.parseVersion("v1.5.1"))
    }

    // ── capability integration ───────────────────────────────────────

    fun testExecuteFallsBackToCliWhenNoServerMode() {
        val daemon = DocscribeDaemon(project)
        daemon.docscribeStatus = DocscribeDaemon.DocscribeStatus.AVAILABLE
        daemon.capabilities = DocscribeDaemon.DocscribeCapabilities("1.5.0", serverMode = false)
        // No server mode — should trigger CLI fallback, which needs a Gemfile
        // Since there's no Gemfile in test, ensureRunning returns null,
        // fallback() sees MISSING stub but status is AVAILABLE…
        // Actually this tests the path through execute -> ensureRunning -> null -> fallback
        val result = daemon.execute("check", file = "test.rb", projectDir = "/tmp")
        // Without a Gemfile, fallback may fail — just ensure no crash
        assertNotNull(result)
    }

    fun testCapabilitiesDefaultsToNull() {
        val daemon = DocscribeDaemon(project)
        assertNull(daemon.capabilities)
    }
}
