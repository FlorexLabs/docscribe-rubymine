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
}
