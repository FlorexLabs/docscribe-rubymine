package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocscribeDaemonTest {
    // --- buildRpcRequestJson edge cases (not covered by RpcProtocolTest) ---

    @Test
    fun `rpc request with special characters in params`() {
        val json = DocscribeDaemon.buildRpcRequestJson("check", mapOf("file" to "my file.rb", "path" to "/a/b/c"))
        assertTrue(json.contains("\"file\":\"my file.rb\""))
        assertTrue(json.contains("\"path\":\"/a/b/c\""))
    }

    @Test
    fun `rpc request with boolean and numeric params`() {
        val json = DocscribeDaemon.buildRpcRequestJson("fix", mapOf("no_boilerplate" to true, "timeout" to 30))
        assertTrue(json.contains("\"no_boilerplate\":true"))
        assertTrue(json.contains("\"timeout\":30"))
    }

    @Test
    fun `rpc request with empty method name`() {
        val json = DocscribeDaemon.buildRpcRequestJson("")
        assertTrue(json.contains("\"method\":\"\""))
    }

    // --- parseRpcResponse edge cases (not covered by RpcProtocolTest) ---

    @Test
    fun `parse non-object JSON returns null`() {
        assertNull(DocscribeDaemon.parseRpcResponse("\"string\""))
        assertNull(DocscribeDaemon.parseRpcResponse("null"))
        assertNull(DocscribeDaemon.parseRpcResponse("[1,2,3]"))
    }

    @Test
    fun `parse response with unicode characters`() {
        val response = """{"jsonrpc":"2.0","result":{"message":"über cool"}}"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        assertEquals("über cool", (parsed!!["result"] as? Map<*, *>)?.get("message"))
    }

    @Test
    fun `parse response with deeply nested result`() {
        val response = """{"jsonrpc":"2.0","result":{"changes":[{"line":1,"text":"# docs"},{"line":5,"text":"# more"}]}}"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        val changes = (parsed!!["result"] as? Map<*, *>)?.get("changes") as? List<*>
        assertEquals(2, changes?.size)
    }

    @Test
    fun `parse response with numeric error code`() {
        val response = """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        assertNotNull(parsed)
        val error = parsed!!["error"] as? Map<*, *>
        assertEquals(-32700.0, error?.get("code"))
        assertEquals("Parse error", error?.get("message"))
    }

    @Test
    fun `parse response with null result field`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":null}"""
        val parsed = DocscribeDaemon.parseRpcResponse(response)
        assertNotNull(parsed)
        assertNull(parsed!!["result"])
    }

    // --- buildCheckJson edge cases (not covered by BuildCheckJsonTest) ---

    @Test
    fun `check json with null entry in changes`() {
        val changes = listOf(null, mapOf("line" to 3))
        val json = DocscribeDaemon.buildCheckJson("f.rb", changes)
        assertTrue(json.contains("\"start_line\":3"))
        assertTrue(json.contains("\"offense_count\":1"))
    }

    @Test
    fun `check json with large line numbers`() {
        val changes = listOf(mapOf("line" to 9999))
        val json = DocscribeDaemon.buildCheckJson("test.rb", changes)
        assertTrue(json.contains("\"start_line\":9999"))
    }

    @Test
    fun `check json includes metadata`() {
        val json = DocscribeDaemon.buildCheckJson("f.rb", emptyList<Any>())
        assertTrue(json.contains("\"docscribe_version\""))
        assertTrue(json.contains("\"metadata\""))
    }

    @Test
    fun `check json summary with multiple offenses`() {
        val changes = listOf(mapOf("line" to 3), mapOf("line" to 7), mapOf("line" to 11))
        val json = DocscribeDaemon.buildCheckJson("f.rb", changes)
        assertTrue(json.contains("\"offense_count\":3"))
        assertTrue(json.contains("\"target_file_count\":1"))
        assertTrue(json.contains("\"inspected_file_count\":1"))
        assertTrue(json.contains("\"error_count\":0"))
    }
}
