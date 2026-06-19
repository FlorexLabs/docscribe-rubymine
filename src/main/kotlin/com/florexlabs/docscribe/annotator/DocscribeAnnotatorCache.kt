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

    /**
     * Returns cached result if the file has not been modified since it was cached.
     */
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
     * Stores a result in the cache.
     */
    fun put(
        projectPath: String,
        filePath: String,
        fileStamp: Long,
        configHash: Int,
        result: DocscribeOutput?,
    ) {
        val key = Key(projectPath, filePath, configHash)
        cache[key] = Entry(fileStamp, result)
    }

    /**
     * Invalidates all cache entries for a given file path.
     */
    @Suppress("unused")
    fun invalidate(filePath: String) {
        cache.keys.removeIf { key -> key.filePath == filePath }
    }

    companion object {
        @JvmStatic
        fun getInstance(): DocscribeAnnotatorCache = ApplicationManager.getApplication().getService(DocscribeAnnotatorCache::class.java)
    }
}
