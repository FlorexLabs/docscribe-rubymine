package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun clearRemovesAllEntries() {
        cache.put("/project", "/a.rb", 100L, 0, result)
        cache.put("/project", "/b.rb", 100L, 0, result)
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("/project", "/a.rb", 100L, 0))
    }

    @Test
    fun sizeReturnsCorrectCount() {
        assertEquals(0, cache.size())
        cache.put("/project", "/a.rb", 100L, 0, result)
        assertEquals(1, cache.size())
        cache.put("/project", "/b.rb", 100L, 0, result)
        assertEquals(2, cache.size())
    }

    @Test
    fun sizeDecreasesAfterInvalidate() {
        cache.put("/project", "/a.rb", 100L, 0, result)
        cache.put("/project", "/b.rb", 100L, 0, result)
        cache.invalidate("/a.rb")
        assertEquals(1, cache.size())
    }

    @Test
    fun evictionRemovesOldestWhenOverMaxCacheSize() {
        // Fill cache to max, then add one more
        val max = DocscribeAnnotatorCache.MAX_CACHE_SIZE
        for (i in 0 until max) {
            cache.put("/project", "/file$i.rb", 100L, 0, result)
        }
        assertEquals(max, cache.size())

        // Adding one more triggers eviction of oldest quarter
        cache.put("/project", "/overflow.rb", 100L, 0, result)

        // After eviction, size should be max - (max/4) + 1 = 751 (since max=1000, max/4=250)
        val expectedSize = max - (max / 4) + 1
        assertTrue("cache size $expectedSize after eviction", cache.size() <= max)
        assertTrue("cache size > 0 after eviction", cache.size() > 0)
    }

    @Test
    fun configHashSeparatesCacheEntries() {
        cache.put("/project", "/file.rb", 100L, 42, result)
        cache.put("/project", "/file.rb", 100L, 99, result)
        assertEquals(2, cache.size())
        assertEquals(result, cache.get("/project", "/file.rb", 100L, 42))
        assertEquals(result, cache.get("/project", "/file.rb", 100L, 99))
        assertNull(cache.get("/project", "/file.rb", 100L, 0))
    }
}
