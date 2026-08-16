package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.actions.CheckWorkspaceAction.Companion.WorkspaceCheckFailedException
import com.florexlabs.docscribe.actions.CheckWorkspaceAction.Companion.runChunkedCheck
import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckWorkspaceChunkingTest {
    private fun batchJson(vararg results: Map<String, Any?>) = DocscribeDaemon.buildBatchCheckJson(results.toList())

    private fun cleanResult(n: Int): RunResult {
        val files = (0 until n).map { mapOf("file" to "$it.rb", "status" to "ok", "changes" to emptyList<Any>()) }
        return RunResult(
            success = true,
            hasIssues = false,
            exitCode = 0,
            stdout = batchJson(*files.toTypedArray()),
            stderr = "",
        )
    }

    @Test
    fun `chunks all files in groups of ten`() {
        val files = (0 until 87).map { "$it.rb" }
        val sizes = mutableListOf<Int>()

        val summary =
            runChunkedCheck(
                files,
                10,
                isCancelled = { false },
                checkCanceled = {},
                onProgress = { _, _ -> },
                executeChunk = {
                    sizes += it.size
                    cleanResult(it.size)
                },
            )

        assertEquals(listOf(10, 10, 10, 10, 10, 10, 10, 10, 7), sizes)
        assertEquals(87, summary.filesChecked)
    }

    @Test
    fun `reports progress before each chunk`() {
        val files = (0 until 25).map { "$it.rb" }
        val processedCounts = mutableListOf<Int>()
        var total = -1

        runChunkedCheck(
            files,
            10,
            isCancelled = { false },
            checkCanceled = {},
            onProgress = { processed, t ->
                processedCounts += processed
                total = t
            },
            executeChunk = { cleanResult(it.size) },
        )

        assertEquals(listOf(0, 10, 20), processedCounts)
        assertEquals(25, total)
    }

    @Test
    fun `stops after cancellation is detected`() {
        val files = (0 until 25).map { "$it.rb" }
        var calls = 0

        val summary =
            runChunkedCheck(
                files,
                10,
                isCancelled = { calls > 2 },
                checkCanceled = {},
                onProgress = { _, _ -> },
                executeChunk = {
                    calls += 1
                    cleanResult(it.size)
                },
            )

        assertEquals(3, calls)
        assertEquals(25, summary.filesChecked)
    }

    @Test
    fun `throws ProcessCanceledException when checkCanceled requests it`() {
        val files = (0 until 25).map { "$it.rb" }
        var calls = 0

        assertThrows(ProcessCanceledException::class.java) {
            runChunkedCheck(
                files,
                10,
                isCancelled = { false },
                checkCanceled = { if (calls > 1) throw ProcessCanceledException() },
                onProgress = { _, _ -> },
                executeChunk = {
                    calls += 1
                    cleanResult(it.size)
                },
            )
        }
        assertEquals(2, calls)
    }

    @Test
    fun `aborts when a chunk fails fatally`() {
        val files = (0 until 25).map { "$it.rb" }
        var calls = 0
        val failed = mutableListOf<Int>()

        assertThrows(WorkspaceCheckFailedException::class.java) {
            runChunkedCheck(
                files,
                10,
                isCancelled = { false },
                checkCanceled = {},
                onProgress = { _, _ -> },
                executeChunk = {
                    calls += 1
                    if (calls == 3) throw WorkspaceCheckFailedException("boom")
                    failed += it.size
                    cleanResult(it.size)
                },
            )
        }

        assertEquals(3, calls)
        assertEquals(listOf(10, 10), failed)
    }

    @Test
    fun `unparsable chunk output still advances progress`() {
        val files = (0 until 10).map { "$it.rb" }
        val processed = mutableListOf<Int>()

        val summary =
            runChunkedCheck(
                files,
                10,
                isCancelled = { false },
                checkCanceled = {},
                onProgress = { p, _ -> processed += p },
                executeChunk = {
                    RunResult(success = true, hasIssues = false, exitCode = 0, stdout = "junk", stderr = "")
                },
            )

        assertEquals(listOf(0), processed)
        assertEquals(0, summary.filesChecked)
        assertEquals(0, summary.issues)
        assertEquals(0, summary.errors)
        assertTrue(summary.filesChecked == 0)
    }

    @Test
    fun `aggregates issues errors and checked files across chunks`() {
        val files = (0 until 25).map { "$it.rb" }
        val chunkResults =
            listOf(
                batchJson(
                    mapOf("file" to "a.rb", "status" to "ok", "changes" to emptyList<Any>()),
                    mapOf("file" to "b.rb", "status" to "fail", "changes" to listOf(mapOf("line" to 3), mapOf("line" to 7))),
                    mapOf("file" to "c.rb", "status" to "error", "error" to "SyntaxError: boom"),
                ),
                batchJson(
                    mapOf("file" to "d.rb", "status" to "ok", "changes" to emptyList<Any>()),
                    mapOf("file" to "e.rb", "status" to "ok", "changes" to emptyList<Any>()),
                ),
                batchJson(
                    mapOf("file" to "f.rb", "status" to "error", "error" to "Timeout"),
                ),
            )
        var calls = 0

        val summary =
            runChunkedCheck(
                files,
                10,
                isCancelled = { false },
                checkCanceled = {},
                onProgress = { _, _ -> },
                executeChunk = {
                    val stdout = chunkResults[calls.coerceAtMost(chunkResults.size - 1)]
                    calls += 1
                    RunResult(success = true, hasIssues = false, exitCode = 1, stdout = stdout, stderr = "")
                },
            )

        assertEquals(2, summary.issues)
        assertEquals(2, summary.errors)
        assertEquals(4, summary.filesChecked)
    }

    @Test
    fun `empty file list runs no chunks`() {
        var calls = 0
        val summary =
            runChunkedCheck(
                emptyList(),
                10,
                isCancelled = { false },
                checkCanceled = {},
                onProgress = { _, _ -> },
                executeChunk = {
                    calls += 1
                    cleanResult(0)
                },
            )

        assertEquals(0, calls)
        assertEquals(0, summary.filesChecked)
    }
}
