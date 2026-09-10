package com.florexlabs.docscribe.runner

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCheckJsonTest {
    private val gson = Gson()

    @Test
    fun `empty changes`() {
        val json = DocscribeDaemon.buildCheckJson("test.rb", emptyList<Any>())
        val output = parse(json)
        assertNotNull(output)
        assertEquals("test.rb", output!!.files.single().path)
        assertTrue(
            output.files
                .single()
                .offenses
                .isEmpty(),
        )
        assertEquals(0, output.summary?.offenseCount)
        assertEquals(1, output.summary?.inspectedFileCount)
    }

    @Test
    fun `single change at line 42`() {
        val changes =
            listOf(
                mapOf(
                    "line" to 42,
                    "original" to "def foo",
                    "updated" to "# docs\\ndef foo",
                ),
            )
        val json = DocscribeDaemon.buildCheckJson("app/models/user.rb", changes)
        val output = parse(json)
        assertNotNull(output)
        assertEquals("app/models/user.rb", output!!.files.single().path)
        assertEquals(
            1,
            output.files
                .single()
                .offenses.size,
        )
        assertEquals(
            42,
            output.files
                .single()
                .offenses[0]
                .location.startLine,
        )
        assertEquals(1, output.summary?.offenseCount)
    }

    @Test
    fun `multiple changes`() {
        val changes =
            listOf(
                mapOf("line" to 10),
                mapOf("line" to 20),
                mapOf("line" to 30),
            )
        val json = DocscribeDaemon.buildCheckJson("lib/helper.rb", changes)
        val output = parse(json)
        assertNotNull(output)
        assertEquals(
            3,
            output!!
                .files
                .single()
                .offenses.size,
        )
        val lines =
            output.files
                .single()
                .offenses
                .map { it.location.startLine }
        assertEquals(listOf(10, 20, 30), lines)
        assertEquals(3, output.summary?.offenseCount)
    }

    @Test
    fun `change without line`() {
        val changes = listOf(mapOf<String, Any>())
        val json = DocscribeDaemon.buildCheckJson("test.rb", changes)
        val output = parse(json)
        assertNotNull(output)
        assertEquals(
            1,
            output!!
                .files
                .single()
                .offenses.size,
        )
        assertEquals(
            1,
            output.files
                .single()
                .offenses[0]
                .location.startLine,
        )
    }

    @Test
    fun `non-map elements in changes are skipped`() {
        val changes = listOf("string", 42, mapOf("line" to 7))
        val json = DocscribeDaemon.buildCheckJson("test.rb", changes)
        val output = parse(json)
        assertNotNull(output)
        assertEquals(
            1,
            output!!
                .files
                .single()
                .offenses.size,
        )
        assertEquals(
            7,
            output.files
                .single()
                .offenses[0]
                .location.startLine,
        )
    }

    @Test
    fun `offense fields are correct`() {
        val changes = listOf(mapOf("line" to 15))
        val json = DocscribeDaemon.buildCheckJson("test.rb", changes)
        val output = parse(json)
        val offense = output!!.files.single().offenses[0]
        assertEquals("convention", offense.severity)
        assertEquals("DocScribe/MissingDocumentation", offense.copName)
        assertEquals("Missing YARD documentation", offense.message)
        assertEquals(false, offense.corrected)
        assertEquals(true, offense.correctable)
        assertEquals(15, offense.location.startLine)
        assertEquals(1, offense.location.startColumn)
        assertEquals(15, offense.location.lastLine)
        assertEquals(1, offense.location.lastColumn)
    }

    @Test
    fun `updated_param change maps to Docscribe UpdatedParam warning`() {
        val changes = listOf(mapOf("line" to 13, "type" to "updated_param", "message" to "updated @param text from Object to Integer"))
        val json = DocscribeDaemon.buildCheckJson("lib/factories/string_factory.rb", changes)
        val output = parse(json)
        val offense = output!!.files.single().offenses[0]
        assertEquals("warning", offense.severity)
        assertEquals("Docscribe/UpdatedParam", offense.copName)
        assertEquals("updated @param text from Object to Integer", offense.message)
        assertEquals(13, offense.location.startLine)
    }

    @Test
    fun `updated_return change maps to Docscribe UpdatedReturn warning`() {
        val changes = listOf(mapOf("line" to 20, "type" to "updated_return", "message" to "updated @return from Object to String"))
        val json = DocscribeDaemon.buildCheckJson("lib/example.rb", changes)
        val output = parse(json)
        val offense = output!!.files.single().offenses[0]
        assertEquals("warning", offense.severity)
        assertEquals("Docscribe/UpdatedReturn", offense.copName)
        assertEquals("updated @return from Object to String", offense.message)
        assertEquals(20, offense.location.startLine)
    }

    @Test
    fun `updated_param without message uses default`() {
        val changes = listOf(mapOf("line" to 5, "type" to "updated_param"))
        val json = DocscribeDaemon.buildCheckJson("test.rb", changes)
        val output = parse(json)
        val offense = output!!.files.single().offenses[0]
        assertEquals("Docscribe/UpdatedParam", offense.copName)
        assertEquals("warning", offense.severity)
        assertEquals("updated @param type from RBS", offense.message)
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
