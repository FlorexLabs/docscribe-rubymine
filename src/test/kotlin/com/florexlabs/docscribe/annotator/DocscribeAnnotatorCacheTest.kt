package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocscribeAnnotatorCacheTest {
    private val cache = DocscribeAnnotatorCache()
    private val result =
        DocscribeOutput(
            metadata = emptyMap(),
            files = emptyList(),
            summary = null,
        )

    @Test
    fun cacheHitReturnsCachedResult() {
        cache.put("/project", "/file.rb", 100L, 0, result)
        val got = cache.get("/project", "/file.rb", 100L, 0)
        assertEquals(result, got)
    }

    @Test
    fun cacheMissReturnsNullForDifferentStamp() {
        cache.put("/project", "/file.rb", 100L, 0, result)
        val got = cache.get("/project", "/file.rb", 200L, 0)
        assertNull(got)
    }

    @Test
    fun cacheMissReturnsNullForDifferentProject() {
        cache.put("/projectA", "/file.rb", 100L, 0, result)
        val got = cache.get("/projectB", "/file.rb", 100L, 0)
        assertNull(got)
    }

    @Test
    fun cacheMissReturnsNullForDifferentConfigHash() {
        cache.put("/project", "/file.rb", 100L, 0, result)
        val got = cache.get("/project", "/file.rb", 100L, 1)
        assertNull(got)
    }

    @Test
    fun invalidateRemovesAllEntriesForFile() {
        cache.put("/project", "/file.rb", 100L, 0, result)
        cache.put("/project", "/file.rb", 100L, 1, result)
        cache.put("/project", "/other.rb", 100L, 0, result)
        cache.invalidate("/file.rb")
        assertNull(cache.get("/project", "/file.rb", 100L, 0))
        assertNull(cache.get("/project", "/file.rb", 100L, 1))
        assertEquals(result, cache.get("/project", "/other.rb", 100L, 0))
    }
}
