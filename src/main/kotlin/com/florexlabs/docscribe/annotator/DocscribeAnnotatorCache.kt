package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeOutput
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level cache for docscribe annotation results.
 *
 * Keyed by (projectPath, filePath, configHash) and validated by file modification stamp.
 * Prevents re-running docscribe on unchanged files between saves.
 *
 * Maximum cache size: [MAX_CACHE_SIZE]. When exceeded, the oldest entries are evicted.
 */
@Service
class DocscribeAnnotatorCache {
    private data class Key(
        val projectPath: String,
        val filePath: String,
        val configHash: Int,
    )

    private data class Entry(
        val fileStamp: Long,
        val result: DocscribeOutput?,
    )

    private val cache = ConcurrentHashMap<Key, Entry>()
    private val insertionOrder = mutableListOf<Key>()

    /**
     * Returns cached result if the file has not been modified since it was cached.
     */
    @Synchronized
    fun get(
        projectPath: String,
        filePath: String,
        fileStamp: Long,
        configHash: Int,
    ): DocscribeOutput? {
        val key = Key(projectPath, filePath, configHash)
        val entry = cache[key] ?: return null
        return if (entry.fileStamp == fileStamp) entry.result else null
    }

    /**
     * Stores a result in the cache. Evicts oldest entries if cache exceeds [MAX_CACHE_SIZE].
     */
    @Synchronized
    fun put(
        projectPath: String,
        filePath: String,
        fileStamp: Long,
        configHash: Int,
        result: DocscribeOutput?,
    ) {
        evictIfNeeded()
        val key = Key(projectPath, filePath, configHash)
        if (cache.put(key, Entry(fileStamp, result)) == null) {
            insertionOrder.add(key)
        }
    }

    /**
     * Invalidates all cache entries for a given file path.
     */
    fun invalidate(filePath: String) {
        cache.keys.removeIf { key -> key.filePath == filePath }
        insertionOrder.removeIf { it.filePath == filePath }
    }

    /**
     * Clears the entire cache.
     */
    fun clear() {
        cache.clear()
        insertionOrder.clear()
    }

    /**
     * Returns the current number of cached entries (for testing and monitoring).
     */
    fun size(): Int = cache.size

    /**
     * If the cache has exceeded [MAX_CACHE_SIZE], removes the oldest quarter of entries.
     */
    private fun evictIfNeeded() {
        while (cache.size >= MAX_CACHE_SIZE) {
            val evictCount = (MAX_CACHE_SIZE / 4).coerceAtLeast(1)
            val toEvict = insertionOrder.take(evictCount)
            insertionOrder.removeAll(toEvict)
            toEvict.forEach { cache.remove(it) }
        }
    }

    companion object {
        /** Maximum number of annotated files to cache. */
        const val MAX_CACHE_SIZE = 1000

        /**
         * Get the application-level [DocscribeAnnotatorCache] singleton.
         */
        @JvmStatic
        fun getInstance(): DocscribeAnnotatorCache = ApplicationManager.getApplication().getService(DocscribeAnnotatorCache::class.java)
    }
}
