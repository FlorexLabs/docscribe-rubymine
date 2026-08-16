package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DocscribeSdkResolutionTest {
    @Test
    fun `resolveRubyHome prefers project sdk home`() {
        assertEquals(
            "/project/ruby",
            DocscribeDaemon.resolveRubyHome("/project/ruby", listOf("/m1/bin/ruby", "/m2")),
        )
    }

    @Test
    fun `resolveRubyHome falls back to first ruby-like module home`() {
        assertEquals(
            "/m2/bin/ruby",
            DocscribeDaemon.resolveRubyHome(null, listOf("/m1/toolchain", "/m2/bin/ruby", "/m3/bin/jruby")),
        )
    }

    @Test
    fun `resolveRubyHome falls back to any module home without ruby-like path`() {
        assertEquals(
            "/m1/whatever",
            DocscribeDaemon.resolveRubyHome(null, listOf("/m1/whatever", "/m2/other")),
        )
    }

    @Test
    fun `resolveRubyHome returns null when no sdk homes configured`() {
        assertNull(DocscribeDaemon.resolveRubyHome(null, emptyList()))
        assertNull(DocscribeDaemon.resolveRubyHome(null, listOf(null, "")))
    }

    @Test
    fun `resolveRubyHome ignores leading blank project home`() {
        assertEquals("/m1/bin/ruby", DocscribeDaemon.resolveRubyHome("", listOf("/m1/bin/ruby")))
    }

    @Test
    fun `bundlePathFor returns absolute sibling bundle when executable`() {
        val dir = createTempDirectory("docscribe-sdk").toFile()
        try {
            val bin = File(dir, "bin").apply { mkdirs() }
            File(bin, "ruby").apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
            }
            val bundle =
                File(bin, "bundle").apply {
                    writeText("#!/bin/sh\necho 1.6.0\n")
                    setExecutable(true)
                }
            assertEquals(
                bundle.absolutePath,
                DocscribeDaemon.bundlePathFor(File(bin, "ruby").absolutePath),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bundlePathFor returns null when bundle is not executable`() {
        val dir = createTempDirectory("docscribe-sdk").toFile()
        try {
            val bin = File(dir, "bin").apply { mkdirs() }
            File(bin, "ruby").apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
            }
            assertNull(DocscribeDaemon.bundlePathFor(File(bin, "ruby").absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bundlePathFor returns null for blank or missing ruby path`() {
        assertNull(DocscribeDaemon.bundlePathFor(null))
        assertNull(DocscribeDaemon.bundlePathFor(""))
        assertNull(DocscribeDaemon.bundlePathFor("/does/not/exist/bin/ruby"))
    }
}
