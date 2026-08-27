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
 * Uses Pseudo-LRU eviction (binary tree approximation) to keep recently edited files hot
 * while evicting cold entries with O(log n) overhead instead of true LRU's O(n).
 * See https://en.wikipedia.org/wiki/Pseudo-LRU
 *
 * Maximum cache size: [MAX_CACHE_SIZE]. When exceeded, the Pseudo-LRU victim is evicted.
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

    // Pseudo-LRU tree: bit 0 = left subtree MRU, 1 = right MRU; leaf count = next power of two >= MAX_CACHE_SIZE
    private val treeBits = java.util.BitSet(2048)
    private var treeSize = 1
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val insertionOrder = mutableListOf<Key>()

    /**
     * Returns cached result if the file has not been modified since it was cached.
     * Updates Pseudo-LRU tree on hit to mark as recently used.
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
        if (entry.fileStamp != fileStamp) return null
        touchPseudoLru(key)
        return entry.result
    }

    /**
     * Stores a result in the cache. Evicts via Pseudo-LRU if cache exceeds [MAX_CACHE_SIZE].
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
        val isNew = cache.put(key, Entry(fileStamp, result)) == null
        if (isNew) {
            insertionOrder.add(key)
            // Initialize tree size to next power of two
            if (treeSize < insertionOrder.size) {
                treeSize = Integer.highestOneBit(insertionOrder.size - 1) shl 1
                if (treeSize < 2) treeSize = 2
            }
        }
        touchPseudoLru(key)
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

    private fun touchPseudoLru(key: Key) {
        val idx = insertionOrder.indexOf(key)
        if (idx == -1) return
        // Walk tree from leaf to root, setting bits to point away from MRU leaf
        var node = idx + treeSize
        var bitPos = 0
        while (node > 1) {
            val parent = node / 2
            val isRight = node % 2 == 1
            // Bit 0 = left MRU, 1 = right MRU — set to opposite of current leaf direction
            if (isRight) treeBits.clear(parent) else treeBits.set(parent)
            node = parent
            bitPos++
            if (bitPos > 20) break // safety
        }
    }

    private fun findPseudoLruVictim(): Key? {
        if (insertionOrder.isEmpty()) return null
        var node = 1
        while (node < treeSize) {
            val bit = treeBits.get(node)
            // Follow the LRU direction (opposite of MRU bit)
            node = if (bit) node * 2 else node * 2 + 1
            if (node >= treeSize + insertionOrder.size) break
        }
        val leafIdx = (node - treeSize).coerceIn(0, insertionOrder.size - 1)
        return insertionOrder.getOrNull(leafIdx)
    }

    /**
     * If the cache has exceeded [MAX_CACHE_SIZE], removes via Pseudo-LRU victim.
     */
    private fun evictIfNeeded() {
        while (cache.size >= MAX_CACHE_SIZE) {
            val victim = findPseudoLruVictim() ?: insertionOrder.firstOrNull() ?: break
            cache.remove(victim)
            insertionOrder.remove(victim)
            // Clear tree bits for victim leaf
            val idx = insertionOrder.indexOf(victim)
            if (idx != -1) treeBits.clear(idx + treeSize)
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
