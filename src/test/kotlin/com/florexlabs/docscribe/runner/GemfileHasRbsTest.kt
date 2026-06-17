package com.florexlabs.docscribe.runner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GemfileHasRbsTest {

    @Rule @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun `returns true when Gemfile includes rbs with double quotes`() {
        val gemfile = File(tempDir.root, "Gemfile")
        gemfile.writeText(
            """
            source "https://rubygems.org"
            gem "rbs"
            """.trimIndent()
        )
        assertTrue(DocscribeRunner.gemfileHasRbs(gemfile.absolutePath))
    }

    @Test
    fun `returns true when Gemfile includes rbs with single quotes`() {
        val gemfile = File(tempDir.root, "Gemfile")
        gemfile.writeText(
            """
            source 'https://rubygems.org'
            gem 'rbs'
            """.trimIndent()
        )
        assertTrue(DocscribeRunner.gemfileHasRbs(gemfile.absolutePath))
    }

    @Test
    fun `returns false when Gemfile does not include rbs`() {
        val gemfile = File(tempDir.root, "Gemfile")
        gemfile.writeText(
            """
            source "https://rubygems.org"
            gem "rspec"
            """.trimIndent()
        )
        assertFalse(DocscribeRunner.gemfileHasRbs(gemfile.absolutePath))
    }

    @Test
    fun `returns false for non existent file`() {
        assertFalse(DocscribeRunner.gemfileHasRbs("/non/existent/Gemfile"))
    }
}
