package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcProtocolTest {
    @Test
    fun `request ends with newline`() {
        val json = DocscribeDaemon.buildRpcRequestJson("ping")
        assertTrue(json.endsWith("\n"))
    }

    @Test
    fun `request has valid JSON-RPC structure`() {
        val json = DocscribeDaemon.buildRpcRequestJson("check", mapOf("file" to "test.rb"))
        val parsed = DocscribeDaemon.parseRpcResponse(json.trimEnd())
        assertNotNull(parsed)
        assertEquals("2.0", parsed!!["jsonrpc"])
        assertEquals(1, (parsed["id"] as? Number)?.toInt())
        assertEquals("check", parsed["method"])
        val params = parsed["params"] as? Map<*, *>
        assertNotNull(params)
        assertEquals("test.rb", params!!["file"])
    }

    @Test
    fun `request includes params map`() {
        val json =
            DocscribeDaemon.buildRpcRequestJson(
                "fix",
                mapOf("file" to "app.rb", "strategy" to "safe"),
            )
        val parsed = DocscribeDaemon.parseRpcResponse(json.trimEnd())
        val params = parsed!!["params"] as Map<*, *>
        assertEquals("app.rb", params["file"])
        assertEquals("safe", params["strategy"])
    }

    @Test
    fun `request with empty params`() {
        val json = DocscribeDaemon.buildRpcRequestJson("shutdown")
        val parsed = DocscribeDaemon.parseRpcResponse(json.trimEnd())
        assertNotNull(parsed)
        assertEquals("shutdown", parsed!!["method"])
        assertNotNull(parsed["params"])
    }

    @Test
    fun `parse valid response`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        assertNotNull(parsed)
        val result = parsed!!["result"] as? Map<*, *>
        assertNotNull(result)
        assertEquals("ok", result!!["status"])
    }

    @Test
    fun `parse response with trailing newline`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":{"changed":true}}
"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        assertNotNull(parsed)
        val result = parsed!!["result"] as? Map<*, *>
        assertNotNull(result)
        assertEquals(true, result!!["changed"])
    }

    @Test
    fun `parse response with error`() {
        val response = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"File not found"}}"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        assertNotNull(parsed)
        val error = parsed!!["error"] as? Map<*, *>
        assertNotNull(error)
        assertEquals(-32602, (error!!["code"] as? Number)?.toInt())
        assertEquals("File not found", error["message"])
    }

    @Test
    fun `parse blank response returns null`() {
        assertNull(DocscribeDaemon.parseRpcResponse(""))
        assertNull(DocscribeDaemon.parseRpcResponse("  "))
    }

    @Test
    fun `parse malformed JSON returns null`() {
        assertNull(DocscribeDaemon.parseRpcResponse("not json"))
    }

    @Test
    fun `request trims to valid JSON`() {
        val json = DocscribeDaemon.buildRpcRequestJson("fix", mapOf("file" to "x.rb"))
        val trimmed = json.trimEnd()
        val parsed = DocscribeDaemon.parseRpcResponse(trimmed)
        assertNotNull(parsed)
        assertEquals("fix", parsed!!["method"])
    }

    @Test
    fun `roundtrip request`() {
        val json = DocscribeDaemon.buildRpcRequestJson("check", mapOf("file" to "/path/to/file.rb"))
        val parsed = DocscribeDaemon.parseRpcResponse(json.trimEnd())
        assertNotNull(parsed)
        assertEquals("check", parsed!!["method"])
        val params = parsed["params"] as Map<*, *>
        assertEquals("/path/to/file.rb", params["file"])
    }
}
