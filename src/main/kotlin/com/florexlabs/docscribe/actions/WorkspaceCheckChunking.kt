@file:Suppress(
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "ReturnCount",
    "UnnecessaryParentheses",
)

package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.RbsDetector
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * Number of files sent to the daemon in one `check_batch` call.
 */
const val WORKSPACE_CHUNK_SIZE = 10

private val LOG = Logger.getInstance("com.florexlabs.docscribe.actions.WorkspaceCheckChunking")

/**
 * Aggregate result of a chunked workspace check.
 *
 * @property filesChecked Number of files with a valid result (errors excluded).
 * @property issues       Total documentation issues found across all chunks.
 * @property errors       Total per-file errors across all chunks.
 */
data class ChunkedCheckSummary(
    val filesChecked: Int,
    val issues: Int,
    val errors: Int,
)

/**
 * Thrown when a chunk fails with a fatal exit code (>= 2), aborting the workspace check.
 *
 * @property message Error detail to show to the user.
 */
class WorkspaceCheckFailedException(
    message: String,
) : RuntimeException(message)

/**
 * Run [executeChunk] over [files] in chunks of [chunkSize], reporting progress between
 * chunks and honouring cancellation.
 *
 * [checkCanceled] is invoked before every chunk; it is expected to throw a
 * `ProcessCanceledException` when the operation was cancelled. [isCancelled] is a
 * defensive non-throwing variant checked right after. Per-chunk JSON output is parsed
 * with [DocscribeOutputParser.parseJson] and the summaries are aggregated.
 *
 * @param files         Absolute paths of the Ruby files to check.
 * @param chunkSize     Maximum number of files per chunk.
 * @param isCancelled   Non-throwing cancellation probe.
 * @param checkCanceled Throwing cancellation probe, called before each chunk.
 * @param onProgress    Called with the number of files processed and the total.
 * @param executeChunk  Runs the actual check for one chunk and returns its [RunResult].
 * @return Aggregated [ChunkedCheckSummary].
 */
internal fun runChunkedCheck(
    files: List<String>,
    chunkSize: Int,
    isCancelled: () -> Boolean,
    checkCanceled: () -> Unit,
    onProgress: (processed: Int, total: Int) -> Unit,
    executeChunk: (List<String>) -> RunResult,
): ChunkedCheckSummary {
    var processed = 0
    var filesChecked = 0
    var issues = 0
    var errors = 0
    for (chunk in files.chunked(chunkSize)) {
        checkCanceled()
        if (isCancelled()) break
        onProgress(processed, files.size)
        val result = executeChunk(chunk)
        val parsed = DocscribeOutputParser.parseJson(result.stdout)
        processed += chunk.size
        if (parsed != null) {
            filesChecked += parsed.summary?.inspectedFileCount ?: 0
            issues += parsed.summary?.offenseCount ?: 0
            errors += parsed.summary?.errorCount ?: 0
        }
    }
    return ChunkedCheckSummary(filesChecked, issues, errors)
}

/**
 * Collect absolute paths of all Ruby files in the project content roots.
 *
 * Only files under the project root directory are considered. Excluded files
 * (e.g. `.git`, `node_modules`, IDE exclusion patterns) are skipped, as are files
 * outside the project content roots. Filtering mirrors `Config#process_file?`:
 * exclude wins, include empty means include all.
 *
 * @param project     The current project.
 * @param projectRoot The project root directory (Gemfile location).
 * @return Absolute paths of the `.rb` / `.rake` / `Rakefile` files, in traversal order.
 */
internal fun collectRubyFiles(
    project: Project,
    projectRoot: String,
): List<String> {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val rootDir = LocalFileSystem.getInstance().findFileByIoFile(File(projectRoot)) ?: return emptyList()
    val collected = mutableListOf<String>()
    val (includePatterns, excludePatterns) = loadFileFilterPatterns(projectRoot)
    LOG.info(
        "DocScribe collectRubyFiles root=$projectRoot include=$includePatterns " +
            "exclude=$excludePatterns rbs=${RbsDetector.shouldUseRbs(projectRoot)} " +
            "hasCollection=${RbsDetector.hasCollection(projectRoot)} rbsHash=${RbsDetector.rbsHash(projectRoot)}",
    )
    ReadAction
        .nonBlocking(
            Callable<Unit> {
                VfsUtilCore.iterateChildrenRecursively(
                    rootDir,
                    { f -> !fileIndex.isExcluded(f) },
                ) { f ->
                    if (!f.isDirectory && isRubyFile(f.name) && !fileIndex.isExcluded(f) && fileIndex.isInContent(f)) {
                        collected.add(f.path)
                    }
                    true
                }
            },
        ).executeSynchronously()
    val filtered =
        collected.filter { path ->
            val rel = relativePath(projectRoot, path)
            when {
                fileMatchesAny(excludePatterns, rel) -> {
                    LOG.debug("DocScribe collectRubyFiles excluded: $rel")
                    false
                }

                includePatterns.isEmpty() -> {
                    true
                }

                else -> {
                    val inc = fileMatchesAny(includePatterns, rel)
                    if (!inc) LOG.debug("DocScribe collectRubyFiles not included: $rel")
                    inc
                }
            }
        }
    LOG.info("DocScribe collectRubyFiles collected=${collected.size} filtered=${filtered.size}")
    return filtered
}

/**
 * Load file include/exclude patterns from `docscribe.yml`, mirroring `Config#load_file_patterns`.
 * Falls back to `exclude: ['spec']` when no config is present.
 */
internal fun loadFileFilterPatterns(projectRoot: String): Pair<List<String>, List<String>> {
    val yml = findDocscribeYml(projectRoot)
    if (yml == null) {
        return Pair(emptyList(), normalizeFilePatterns(listOf("spec"), projectRoot))
    }
    return try {
        val content = yml.readText()
        val (rawInclude, rawExclude) = parseFilterFilesPatterns(content)
        val include = normalizeFilePatterns(rawInclude, projectRoot)
        val exclude =
            if (rawExclude.isEmpty()) {
                normalizeFilePatterns(listOf("spec"), projectRoot)
            } else {
                normalizeFilePatterns(rawExclude, projectRoot)
            }
        Pair(include, exclude)
    } catch (_: Exception) {
        Pair(emptyList(), normalizeFilePatterns(listOf("spec"), projectRoot))
    }
}

private fun findDocscribeYml(projectRoot: String): File? {
    val candidates = listOf(File(projectRoot, "docscribe.yml"), File(projectRoot, ".docscribe.yml"))
    return candidates.firstOrNull { it.isFile }
}

internal fun parseFilterFilesPatterns(content: String): Pair<List<String>, List<String>> {
    val lines = content.lines()
    var inFilter = false
    var inFiles = false
    val rawInclude = mutableListOf<String>()
    val rawExclude = mutableListOf<String>()
    var current: MutableList<String>? = null
    for (raw in lines) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("filter:")) {
            inFilter = true
            continue
        }
        if (inFilter && trimmed.startsWith("files:")) {
            inFiles = true
            continue
        }
        if (!inFiles) continue
        when {
            trimmed.startsWith("include:") -> {
                val inline = trimmed.substringAfter("include:").trim()
                if (inline.startsWith("[")) {
                    rawInclude.addAll(parseInlineList(inline))
                    current = null
                } else {
                    current = rawInclude
                }
            }

            trimmed.startsWith("exclude:") -> {
                val inline = trimmed.substringAfter("exclude:").trim()
                if (inline.startsWith("[")) {
                    rawExclude.addAll(parseInlineList(inline))
                    current = null
                } else {
                    current = rawExclude
                }
            }

            trimmed.startsWith("- ") && current != null -> {
                val pat =
                    trimmed
                        .removePrefix("- ")
                        .trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                if (pat.isNotEmpty()) current.add(pat)
            }

            trimmed.endsWith(":") && !trimmed.startsWith("-") -> {
                // New key inside files or filter, reset if not include/exclude
                if (!trimmed.startsWith("include:") && !trimmed.startsWith("exclude:")) {
                    // If we encounter a sibling of files (e.g., rbs:), exit files section
                    if (raw.trim().length - raw.trimStart().length <= 0) {
                        // top-level key, exit filter/files
                    }
                    current = null
                }
            }

            trimmed.isEmpty() -> {
                current = null
            }
        }
        // Heuristic: if we hit a top-level non-indented key after filter/files, stop
        if (inFiles && raw.isNotEmpty() && !raw.startsWith(" ") && !raw.startsWith("\t") && trimmed.contains(":")) {
            if (!trimmed.startsWith("filter:") && !trimmed.startsWith("files:") &&
                !trimmed.startsWith("include:") && !trimmed.startsWith("exclude:") && !trimmed.startsWith("-")
            ) {
                break
            }
        }
    }
    return Pair(rawInclude, rawExclude)
}

private fun parseInlineList(inline: String): List<String> {
    val inner = inline.removePrefix("[").removeSuffix("]").trim()
    if (inner.isEmpty()) return emptyList()
    return inner.split(",").map { it.trim().removeSurrounding("\"").removeSurrounding("'") }.filter { it.isNotEmpty() }
}

internal fun normalizeFilePatterns(
    patterns: List<String>,
    projectRoot: String,
): List<String> =
    patterns.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.flatMap { expandDirectoryShorthand(it, projectRoot) }.distinct()

private fun expandDirectoryShorthand(
    pattern: String,
    projectRoot: String,
): List<String> {
    val pat = pattern.trim()
    if (pat.endsWith("/")) return listOf("$pat**/*")
    val hasGlob = pat.contains("*") || pat.contains("?") || pat.contains("[") || pat.contains("{")
    if (!hasGlob && File(projectRoot, pat).isDirectory) return listOf("$pat/**/*")
    return listOf(pat)
}

internal fun relativePath(
    projectRoot: String,
    path: String,
): String =
    try {
        val root = Paths.get(projectRoot).toAbsolutePath().normalize()
        val target = Paths.get(path).toAbsolutePath().normalize()
        if (target.startsWith(root)) root.relativize(target).toString() else path
    } catch (_: Exception) {
        path
    }

internal fun fileMatchesAny(
    patterns: List<String>,
    path: String,
): Boolean = patterns.any { fileMatchPattern(it, path) }

internal fun fileMatchPattern(
    pattern: String,
    path: String,
): Boolean {
    if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length >= 2) {
        return try {
            Regex(pattern.substring(1, pattern.length - 1)).containsMatchIn(path)
        } catch (_: Exception) {
            false
        }
    }
    val variants = mutableListOf(pattern)
    if (pattern.contains("/**/")) variants.add(pattern.replace("/**/", "/"))
    return variants.any { pat ->
        try {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pat")
            matcher.matches(Paths.get(path))
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Whether a file name is a Ruby file handled by docscribe.
 *
 * @param name The file name (with extension).
 * @return `true` for `.rb`, `.rake`, and `Rakefile`.
 */
internal fun isRubyFile(name: String): Boolean = name.endsWith(".rb") || name.endsWith(".rake") || name == "Rakefile"
