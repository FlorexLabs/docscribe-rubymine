package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseTextOutputTest {
    @Test
    fun `parses OK summary`() {
        val result =
            DocscribeOutputParser.parseTextOutput(
                "Docscribe: OK (5 files checked)",
            )!!
        assertEquals("OK", result.summary.status)
        assertEquals(5, result.summary.inspectedCount)
    }

    @Test
    fun `parses OK summary with type mismatches`() {
        val result =
            DocscribeOutputParser.parseTextOutput(
                "Docscribe: OK (5 files checked, 2 with type mismatches)",
            )!!
        assertEquals("OK", result.summary.status)
        assertEquals(5, result.summary.inspectedCount)
        assertEquals(2, result.summary.typeMismatchCount)
    }

    @Test
    fun `parses FAILED summary`() {
        val result =
            DocscribeOutputParser.parseTextOutput(
                "Docscribe: FAILED (2 need updates, 1 type mismatches, 0 errors, 3 ok)",
            )!!
        assertEquals("FAILED", result.summary.status)
        assertEquals(2, result.summary.needsUpdateCount)
        assertEquals(1, result.summary.typeMismatchCount)
        assertEquals(0, result.summary.errorCount)
        assertEquals(3, result.summary.okCount)
    }

    @Test
    fun `parses Would update sections`() {
        val text =
            """
            Would update: foo/bar.rb
              - missing @param [String] name for Foo#bar at line 5
              - missing @return [Integer] for Foo#bar at line 5
            Would update: lib/utils.rb
              - missing @param [String] input for Utils.parse at line 12
            Docscribe: FAILED (2 need updates, 0 type mismatches, 0 errors, 3 ok)
            """.trimIndent()
        val result = DocscribeOutputParser.parseTextOutput(text)!!
        assertEquals(2, result.wouldUpdateFiles.size)
        assertEquals("foo/bar.rb", result.wouldUpdateFiles[0].first)
        assertEquals(2, result.wouldUpdateFiles[0].second.size)
        assertEquals("lib/utils.rb", result.wouldUpdateFiles[1].first)
        assertEquals(1, result.wouldUpdateFiles[1].second.size)
    }

    @Test
    fun `parses type mismatches and error sections`() {
        val text =
            """
            Type mismatches: types.rb
            Error processing: broken.rb
            Docscribe: FAILED (0 need updates, 1 type mismatches, 1 errors, 0 ok)
            """.trimIndent()
        val result = DocscribeOutputParser.parseTextOutput(text)!!
        assertEquals(listOf("types.rb"), result.typeMismatchFiles)
        assertEquals(listOf("broken.rb"), result.errorFiles)
    }

    @Test
    fun `parses updated summary for write mode`() {
        val result =
            DocscribeOutputParser.parseTextOutput(
                "Docscribe: updated 3 file(s)",
            )!!
        assertEquals("UPDATED", result.summary.status)
        assertEquals(3, result.summary.updatedCount)
    }

    @Test
    fun `returns null for empty text`() {
        assertNull(DocscribeOutputParser.parseTextOutput(""))
    }

    @Test
    fun `returns null for text without Docscribe line`() {
        assertNull(DocscribeOutputParser.parseTextOutput("some random output"))
    }
}
