package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RbsDetectorTest {
    @Test
    fun `parseRbsEnabled true`() {
        assertEquals(true, RbsDetector.parseRbsEnabled("rbs:\n  enabled: true"))
        assertEquals(true, RbsDetector.parseRbsEnabled("rbs:\n  enabled: 'true'"))
    }

    @Test
    fun `parseRbsEnabled false`() {
        assertEquals(false, RbsDetector.parseRbsEnabled("rbs:\n  enabled: false"))
    }

    @Test
    fun `parseRbsEnabled missing returns null`() {
        assertEquals(null, RbsDetector.parseRbsEnabled("emit:\n  header: false"))
        assertEquals(null, RbsDetector.parseRbsEnabled(""))
    }

    @Test
    fun `shouldUseRbs false when blank dir`() {
        assertFalse(RbsDetector.shouldUseRbs(""))
        assertFalse(RbsDetector.shouldUseRbs("  "))
    }

    @Test
    fun `shouldUseRbs detects sig files`() {
        val dir = Files.createTempDirectory("rbs-test-sig").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "foo.rbs").writeText("class Foo; end")
            assertTrue(RbsDetector.shouldUseRbs(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shouldUseRbs respects explicit false in docscribe yml`() {
        val dir = Files.createTempDirectory("rbs-test-yml").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "foo.rbs").writeText("class Foo; end")
            File(dir, "docscribe.yml").writeText("rbs:\n  enabled: false\n")
            assertFalse(RbsDetector.shouldUseRbs(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shouldUseRbs detects rbs in Gemfile lock`() {
        val dir = Files.createTempDirectory("rbs-test-lock").toFile()
        try {
            File(dir, "Gemfile.lock").writeText("GEM\n  specs:\n    rbs (4.1.3)\n")
            assertTrue(RbsDetector.shouldUseRbs(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hasCollection detects lock file`() {
        val dir = Files.createTempDirectory("rbs-test-collection").toFile()
        try {
            assertFalse(RbsDetector.hasCollection(dir.absolutePath))
            File(dir, "rbs_collection.lock.yaml").createNewFile()
            assertTrue(RbsDetector.hasCollection(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rbsHash changes when sig file mtime changes`() {
        val dir = Files.createTempDirectory("rbs-test-hash").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            val rbsFile = File(sig, "a.rbs")
            rbsFile.writeText("class A; end")
            val hash1 = RbsDetector.rbsHash(dir.absolutePath)
            Thread.sleep(10)
            rbsFile.setLastModified(System.currentTimeMillis() + 1000)
            val hash2 = RbsDetector.rbsHash(dir.absolutePath)
            assertTrue("rbsHash should change when sig mtime changes", hash1 != hash2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rbsHash is cached within TTL when mtime unchanged`() {
        val dir = Files.createTempDirectory("rbs-test-cache").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "a.rbs").writeText("class A; end")
            val hash1 = RbsDetector.rbsHash(dir.absolutePath)
            val hash2 = RbsDetector.rbsHash(dir.absolutePath)
            assertEquals(hash1, hash2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rbsHash includes hasCollection and yml`() {
        val dir = Files.createTempDirectory("rbs-test-hash2").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "a.rbs").writeText("class A; end")
            val hash1 = RbsDetector.rbsHash(dir.absolutePath)
            File(dir, "rbs_collection.lock.yaml").createNewFile()
            Thread.sleep(10)
            // Force mtime change to bypass TTL
            File(sig, "a.rbs").setLastModified(System.currentTimeMillis() + 1000)
            val hash2 = RbsDetector.rbsHash(dir.absolutePath)
            assertTrue(hash1 != hash2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rbsHash single walk handles multiple files`() {
        val dir = Files.createTempDirectory("rbs-test-multi").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "a.rbs").writeText("class A; end")
            File(sig, "b.rbs").writeText("class B; end")
            File(sig, "c.rbs").writeText("class C; end")
            val hash1 = RbsDetector.rbsHash(dir.absolutePath)
            assertTrue(hash1 != 0)
            // Add another file — hash should change
            Thread.sleep(10)
            File(sig, "d.rbs").writeText("class D; end")
            val hash2 = RbsDetector.rbsHash(dir.absolutePath)
            assertTrue(hash1 != hash2)
        } finally {
            dir.deleteRecursively()
        }
    }
}
