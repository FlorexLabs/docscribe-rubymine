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
    fun hasCollection(projectDir: String): Boolean {
        if (projectDir.isBlank()) return false
        return File(projectDir, "rbs_collection.lock.yaml").exists()
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
        if (!sigDir.isDirectory) return false
        return try {
            sigDir.walkTopDown().any { it.isFile && it.extension == "rbs" }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasRbsInLock(projectDir: String): Boolean {
        val lock = File(projectDir, "Gemfile.lock")
        if (!lock.isFile) return false
        return try {
            val text = lock.readText()
            // Gemfile.lock lists gems as indented "    rbs (4.1.3)"
            Regex("""^\s+rbs\s\(""", RegexOption.MULTILINE).containsMatchIn(text)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasRbsInGemfile(projectDir: String): Boolean {
        val gemfile = File(projectDir, "Gemfile")
        if (!gemfile.isFile) return false
        return try {
            val text = gemfile.readText()
            Regex("""gem\s+['"]rbs['"]""").containsMatchIn(text)
        } catch (_: Exception) {
            false
        }
    }
}
