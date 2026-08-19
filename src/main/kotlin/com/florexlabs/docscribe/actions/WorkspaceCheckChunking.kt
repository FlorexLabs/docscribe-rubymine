package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

/**
 * Number of files sent to the daemon in one `check_batch` call.
 */
const val WORKSPACE_CHUNK_SIZE = 10

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
 * outside the project content roots.
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
    ReadAction
        .nonBlocking(
            java.util.concurrent.Callable<Unit> {
                VfsUtilCore.iterateChildrenRecursively(
                    rootDir,
                    { f -> !fileIndex.isExcluded(f) },
                ) { f ->
                    if (!f.isDirectory && isRubyFile(f.name) && fileIndex.isInContent(f)) {
                        collected.add(f.path)
                    }
                    true
                }
            },
        ).executeSynchronously()
    return collected
}

/**
 * Whether a file name is a Ruby file handled by docscribe.
 *
 * @param name The file name (with extension).
 * @return `true` for `.rb`, `.rake`, and `Rakefile`.
 */
internal fun isRubyFile(name: String): Boolean = name.endsWith(".rb") || name.endsWith(".rake") || name == "Rakefile"
