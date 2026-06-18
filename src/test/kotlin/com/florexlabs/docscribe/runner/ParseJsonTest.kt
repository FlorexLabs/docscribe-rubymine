package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseJsonTest {
    @Test
    fun `parses valid JSON output with offenses`() {
        val json =
            """
            {
                "metadata": { "docscribe_version": "1.5.0", "ruby_version": "3.1.0" },
                "files": [
                    {
                        "path": "src/app.rb",
                        "offenses": [
                            {
                                "severity": "convention",
                                "cop_name": "Docscribe/MissingParam",
                                "message": "missing @param [String] name for Foo#bar at line 5",
                                "corrected": false,
                                "correctable": true,
                                "location": {
                                    "start_line": 5,
                                    "start_column": 1,
                                    "last_line": 5,
                                    "last_column": 1
                                }
                            }
                        ]
                    }
                ],
                "summary": {
                    "offense_count": 1,
                    "target_file_count": 1,
                    "inspected_file_count": 5,
                    "error_count": 0
                }
            }
            """.trimIndent()
        val result = DocscribeOutputParser.parseJson(json)!!
        assertEquals("1.5.0", result.metadata?.get("docscribe_version"))
        assertEquals(1, result.files.size)
        assertEquals("src/app.rb", result.files[0].path)
        assertEquals(1, result.files[0].offenses.size)
        val offense = result.files[0].offenses[0]
        assertEquals("convention", offense.severity)
        assertEquals("Docscribe/MissingParam", offense.copName)
        assertEquals(5, offense.location.startLine)
        assertEquals(1, result.summary?.offenseCount)
    }

    @Test
    fun `parses JSON with empty files`() {
        val json =
            """
            {
                "metadata": { "docscribe_version": "1.5.0", "ruby_version": "3.1.0" },
                "files": [],
                "summary": {
                    "offense_count": 0,
                    "target_file_count": 0,
                    "inspected_file_count": 5,
                    "error_count": 0
                }
            }
            """.trimIndent()
        val result = DocscribeOutputParser.parseJson(json)!!
        assertTrue(result.files.isEmpty())
        assertEquals(0, result.summary?.offenseCount)
        assertEquals(5, result.summary?.inspectedFileCount)
    }

    @Test
    fun `returns null for malformed JSON`() {
        assertNull(DocscribeOutputParser.parseJson("not json"))
    }
}
