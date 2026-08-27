package com.florexlabs.docscribe.runner

import java.io.File

/**
 * Detects whether RBS should be enabled for a docscribe run.
 *
 * Hybrid minimal (council #417): respect explicit `docscribe.yml` `rbs.enabled` if present,
 * otherwise auto-detect via `sig/` or `rbs` in Gemfile.lock / Gemfile.
 */
object RbsDetector {
    /**
     * Whether RBS type information should be used for the project at [projectDir].
     *
     * @param projectDir Absolute path to project root.
     * @return true if RBS should be enabled.
     */
    @Suppress("ReturnCount")
    fun shouldUseRbs(projectDir: String): Boolean {
        if (projectDir.isBlank()) return false
        val explicit = readExplicitRbsEnabled(projectDir)
        if (explicit != null) return explicit
        if (hasSigFiles(projectDir)) return true
        if (hasRbsInLock(projectDir)) return true
        if (hasRbsInGemfile(projectDir)) return true
        return false
    }

    /**
     * Whether `rbs_collection.lock.yaml` exists (implies `--rbs-collection`).
     *
     * @param projectDir Project root.
     * @return true if collection lock file exists.
     */
    fun hasCollection(projectDir: String): Boolean = projectDir.isNotBlank() && File(projectDir, "rbs_collection.lock.yaml").exists()

    private data class CachedHash(val hash: Int, val timestamp: Long, val sigMtime: Long)
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, CachedHash>()
    private const val HASH_CACHE_TTL_MS = 1000L

    /**
     * Hash capturing RBS-relevant file states for cache invalidation.
     * Includes enabled flag, collection presence, docscribe.yml mtime and RBS files in sig.
     * Cached for 1 second to avoid repeated file I/O on EDT during typing, with sig mtime check.
     */
    @Suppress("MagicNumber")
    fun rbsHash(projectDir: String): Int {
        if (projectDir.isBlank()) return 0
        val now = System.currentTimeMillis()
        val sigDir = File(projectDir, "sig")
        val sigMtime = if (sigDir.isDirectory) sigDir.walkTopDown().filter { it.isFile && it.extension == "rbs" }.map { it.lastModified() }.maxOrNull() ?: 0 else 0
        hashCache[projectDir]?.let { cached ->
            if (now - cached.timestamp < HASH_CACHE_TTL_MS && sigMtime == cached.sigMtime) return cached.hash
        }
        var hash = shouldUseRbs(projectDir).hashCode()
        hash = 31 * hash + hasCollection(projectDir).hashCode()
        findDocscribeYml(projectDir)?.let { hash = 31 * hash + it.lastModified().hashCode() }
        if (sigDir.isDirectory) {
            try {
                sigDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "rbs" }
                    .forEach {
                        hash = 31 * hash + it.name.hashCode()
                        hash = 31 * hash + it.lastModified().hashCode()
                        hash = 31 * hash + it.length().hashCode()
                    }
            } catch (_: Exception) {
                // ignore walk errors
            }
        }
        hashCache[projectDir] = CachedHash(hash, now, sigMtime)
        return hash
    }

    private fun readExplicitRbsEnabled(projectDir: String): Boolean? {
        val yml = findDocscribeYml(projectDir) ?: return null
        return try {
            val content = yml.readText()
            parseRbsEnabled(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun findDocscribeYml(projectDir: String): File? {
        val candidates =
            listOf(
                File(projectDir, "docscribe.yml"),
                File(projectDir, ".docscribe.yml"),
            )
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * Parse explicit `rbs.enabled` from raw YAML text.
     * Looks for `rbs:` block then `enabled: true|false` inside it.
     */
    internal fun parseRbsEnabled(content: String): Boolean? {
        // Find rbs: section and enabled within next ~10 lines
        val rbsIndex = content.indexOf("rbs:")
        if (rbsIndex == -1) return null
        val tail = content.substring(rbsIndex, minOf(content.length, rbsIndex + 2000))
        // Match enabled: true/false (allow quotes)
        val regex = Regex("""enabled\s*:\s*["']?(true|false)["']?""", RegexOption.IGNORE_CASE)
        val match = regex.find(tail) ?: return null
        return match.groupValues[1].equals("true", ignoreCase = true)
    }

    private fun hasSigFiles(projectDir: String): Boolean {
        val sigDir = File(projectDir, "sig")
        return sigDir.isDirectory &&
            try {
                sigDir.walkTopDown().any { it.isFile && it.extension == "rbs" }
            } catch (_: Exception) {
                false
            }
    }

    private fun hasRbsInLock(projectDir: String): Boolean {
        val lock = File(projectDir, "Gemfile.lock")
        return lock.isFile &&
            try {
                val text = lock.readText()
                // Gemfile.lock lists gems as indented "    rbs (4.1.3)"
                Regex("""^\s+rbs\s\(""", RegexOption.MULTILINE).containsMatchIn(text)
            } catch (_: Exception) {
                false
            }
    }

    private fun hasRbsInGemfile(projectDir: String): Boolean {
        val gemfile = File(projectDir, "Gemfile")
        return gemfile.isFile &&
            try {
                val text = gemfile.readText()
                Regex("""gem\s+['"]rbs['"]""").containsMatchIn(text)
            } catch (_: Exception) {
                false
            }
    }
}
