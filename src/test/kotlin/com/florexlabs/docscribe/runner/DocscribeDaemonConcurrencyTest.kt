package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DocscribeDaemonConcurrencyTest {
    @Test
    fun `buildUpdateTypesParams still includes dir after lock fix`() {
        val params = DocscribeDaemon.buildUpdateTypesParams("/tmp/proj")
        assertEquals("/tmp/proj", params["dir"])
    }

    @Test
    fun `concurrent buildUpdateTypesParams does not deadlock`() {
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(8)
        val errors = mutableListOf<Throwable>()
        repeat(8) { idx ->
            executor.submit {
                try {
                    val params = DocscribeDaemon.buildUpdateTypesParams("/tmp/proj$idx")
                    assertNotNull(params["dir"])
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("No errors in concurrent calls: $errors", errors.isEmpty())
        executor.shutdown()
    }

    @Test
    fun `concurrent rbsHash does not deadlock`() {
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(8)
        val errors = mutableListOf<Throwable>()
        val tmpDir =
            java.nio.file.Files
                .createTempDirectory("rbs-concurrent")
                .toFile()
        try {
            val sig = java.io.File(tmpDir, "sig")
            sig.mkdir()
            java.io.File(sig, "a.rbs").writeText("class A; end")
            repeat(8) {
                executor.submit {
                    try {
                        val h = RbsDetector.rbsHash(tmpDir.absolutePath)
                        assertTrue(h != 0)
                    } catch (e: Throwable) {
                        synchronized(errors) { errors.add(e) }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty())
        } finally {
            tmpDir.deleteRecursively()
            executor.shutdown()
        }
    }
}
