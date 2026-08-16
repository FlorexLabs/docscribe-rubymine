package com.florexlabs.docscribe.runner

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckBatchTest {
    private val gson = Gson()

    @Test
    fun `all clean files`() {
        val results =
            listOf(
                mapOf("file" to "a.rb", "status" to "ok", "changes" to emptyList<Any>()),
                mapOf("file" to "b.rb", "status" to "ok", "changes" to emptyList<Any>()),
            )
        val json = DocscribeDaemon.buildBatchCheckJson(results)
        val output = parse(json)
        assertNotNull(output)
        assertEquals(2, output!!.files.size)
        assertEquals(0, output.summary?.offenseCount)
        assertEquals(2, output.summary?.inspectedFileCount)
        assertEquals(0, output.summary?.errorCount)
    }

    @Test
    fun `mixed ok fail error results`() {
        val results =
            listOf(
                mapOf(
                    "file" to "a.rb",
                    "status" to "ok",
                    "changes" to emptyList<Any>(),
                ),
                mapOf(
                    "file" to "b.rb",
                    "status" to "fail",
                    "changes" to listOf(mapOf("line" to 3), mapOf("line" to 7)),
                ),
                mapOf("file" to "c.rb", "status" to "error", "error" to "SyntaxError: boom"),
            )
        val json = DocscribeDaemon.buildBatchCheckJson(results)
        val output = parse(json)
        assertNotNull(output)
        assertEquals(2, output!!.files.size)
        assertEquals(listOf("a.rb", "b.rb"), output.files.map { it.path })
        assertEquals(
            2,
            output
                .files
                .single { it.path == "b.rb" }
                .offenses.size,
        )
        assertEquals(2, output.summary?.offenseCount)
        assertEquals(3, output.summary?.targetFileCount)
        assertEquals(2, output.summary?.inspectedFileCount)
        assertEquals(1, output.summary?.errorCount)
    }

    @Test
    fun `all errors produces no file entries`() {
        val results =
            listOf(
                mapOf("file" to "a.rb", "status" to "error", "error" to "Timeout"),
                mapOf("file" to "b.rb", "status" to "error", "error" to "File not found: b.rb"),
            )
        val json = DocscribeDaemon.buildBatchCheckJson(results)
        val output = parse(json)
        assertNotNull(output)
        assertTrue(output!!.files.isEmpty())
        assertEquals(0, output.summary?.offenseCount)
        assertEquals(2, output.summary?.errorCount)
        assertEquals(0, output.summary?.inspectedFileCount)
    }

    @Test
    fun `empty batch results`() {
        val json = DocscribeDaemon.buildBatchCheckJson(emptyList<Any>())
        val output = parse(json)
        assertNotNull(output)
        assertTrue(output!!.files.isEmpty())
        assertEquals(0, output.summary?.offenseCount)
        assertEquals(0, output.summary?.errorCount)
        assertEquals(0, output.summary?.targetFileCount)
    }

    @Test
    fun `non-map results are skipped`() {
        val results = listOf("junk", 42, mapOf("file" to "a.rb", "status" to "ok", "changes" to emptyList<Any>()))
        val json = DocscribeDaemon.buildBatchCheckJson(results)
        val output = parse(json)
        assertNotNull(output)
        assertEquals(1, output!!.files.size)
        assertEquals(1, output.summary?.targetFileCount)
    }

    @Test
    fun `result without file is skipped`() {
        val results = listOf(mapOf("status" to "ok", "changes" to emptyList<Any>()))
        val json = DocscribeDaemon.buildBatchCheckJson(results)
        val output = parse(json)
        assertNotNull(output)
        assertTrue(output!!.files.isEmpty())
        assertEquals(0, output.summary?.targetFileCount)
    }

    @Test
    fun `fail result line numbers preserved`() {
        val results =
            listOf(
                mapOf(
                    "file" to "lib/helper.rb",
                    "status" to "fail",
                    "changes" to listOf(mapOf("line" to 42)),
                ),
            )
        val json = DocscribeDaemon.buildBatchCheckJson(results)
        val output = parse(json)
        assertNotNull(output)
        val offense =
            output!!
                .files
                .single()
                .offenses
                .single()
        assertEquals(42, offense.location.startLine)
        assertEquals("convention", offense.severity)
        assertEquals("DocScribe/MissingDocumentation", offense.copName)
    }

    private fun parse(jsonString: String): DocscribeOutput? {
        val type = object : TypeToken<DocscribeOutput>() {}.type
        return try {
            gson.fromJson<DocscribeOutput>(jsonString, type)
        } catch (_: Exception) {
            null
        }
    }
}
