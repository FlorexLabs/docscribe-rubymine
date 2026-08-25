package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
        val response =
            """{"jsonrpc":"2.0","result":{"changes":[{"line":1,"text":"# docs"},{"line":5,"text":"# more"}]}}"""
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

    // --- server socket liveness (idle-timeout restart) ---

    @Test
    fun `socket is alive while the file exists`() {
        val dir = Files.createTempDirectory("docscribe-sock-test")
        val sock = dir.resolve("docscribe.sock")
        assertTrue(Files.createFile(sock).toFile().exists())
        try {
            assertTrue(isServerSocketAlive(sock))
        } finally {
            Files.deleteIfExists(sock)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `socket is dead after the file is removed`() {
        val dir = Files.createTempDirectory("docscribe-sock-test")
        val sock = dir.resolve("docscribe.sock")
        assertTrue(Files.createFile(sock).toFile().exists())
        Files.delete(sock)
        try {
            assertFalse(isServerSocketAlive(sock))
        } finally {
            Files
                .deleteIfExists(dir)
        }
    }

    // --- locale configuration (non-ASCII sources under no-launch-locale) ---

    @Test
    fun `locale defaults to utf8 when LANG is unset`() {
        val env = mutableMapOf<String, String>()
        configureLocaleEnv(env, systemLang = null)
        assertEquals("en_US.UTF-8", env["LANG"])
        assertEquals("en_US.UTF-8", env["LC_ALL"])
    }

    @Test
    fun `locale defaults to utf8 when LANG is blank`() {
        val env = mutableMapOf<String, String>()
        configureLocaleEnv(env, systemLang = "  ")
        assertEquals("en_US.UTF-8", env["LANG"])
        assertEquals("en_US.UTF-8", env["LC_ALL"])
    }

    @Test
    fun `locale preserves the system LANG when set`() {
        val env = mutableMapOf<String, String>()
        configureLocaleEnv(env, systemLang = "ru_RU.UTF-8")
        assertEquals("ru_RU.UTF-8", env["LANG"])
        assertEquals("ru_RU.UTF-8", env["LC_ALL"])
    }

    // --- update_types handling (fix/daemon-update-types) ---

    @Test
    fun `isUnknownMethodError true for -32601`() {
        val resp = mapOf("error" to mapOf("code" to -32601, "message" to "Unknown method: update_types"))
        assertTrue(DocscribeDaemon.isUnknownMethodError(resp))
    }

    @Test
    fun `isUnknownMethodError false for other codes`() {
        assertFalse(DocscribeDaemon.isUnknownMethodError(mapOf("error" to mapOf("code" to -32600))))
        assertFalse(DocscribeDaemon.isUnknownMethodError(mapOf("result" to mapOf("ok" to true))))
        assertFalse(DocscribeDaemon.isUnknownMethodError(null))
        assertFalse(DocscribeDaemon.isUnknownMethodError(mapOf("error" to mapOf("message" to "no code"))))
    }

    @Test
    fun `buildUpdateTypesParams includes dir`() {
        val params = DocscribeDaemon.buildUpdateTypesParams("/tmp/myproject")
        assertEquals("/tmp/myproject", params["dir"])
    }

    @Test
    fun `buildUpdateTypesParams includes rbs cli_overrides when sig exists`() {
        val dir = Files.createTempDirectory("rbs-update-types").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "a.rbs").writeText("class A; end")
            val params = DocscribeDaemon.buildUpdateTypesParams(dir.absolutePath)
            assertEquals(dir.absolutePath, params["dir"])
            @Suppress("UNCHECKED_CAST")
            val overrides = params["cli_overrides"] as? Map<String, Any?>
            assertNotNull(overrides)
            assertEquals(true, overrides?.get("rbs"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildBatchParams includes rbs cli_overrides when sig exists`() {
        val dir = Files.createTempDirectory("rbs-batch").toFile()
        try {
            val sig = File(dir, "sig")
            sig.mkdir()
            File(sig, "b.rbs").writeText("class B; end")
            val params = DocscribeDaemon.buildBatchParams(listOf("/tmp/a.rb"), dir.absolutePath)

            @Suppress("UNCHECKED_CAST")
            val overrides = params["cli_overrides"] as? Map<String, Any?>
            assertNotNull(overrides)
            assertEquals(true, overrides?.get("rbs"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildBatchParams no rbs overrides when no sig`() {
        val dir = Files.createTempDirectory("rbs-batch-no").toFile()
        try {
            val params = DocscribeDaemon.buildBatchParams(listOf("/tmp/a.rb"), dir.absolutePath)
            assertNull(params["cli_overrides"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
