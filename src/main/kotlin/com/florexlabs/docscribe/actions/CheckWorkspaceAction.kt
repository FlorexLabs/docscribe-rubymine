package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

/**
 * Run docscribe **check** on all Ruby files in the project workspace.
 *
 * Enumerates `.rb` / `.rake` / `Rakefile` files in the project content roots and sends them
 * to the daemon in `check_batch` chunks of [WORKSPACE_CHUNK_SIZE] files (falling back to the
 * CLI directory scan when the daemon or batch support is unavailable). Runs in background via
 * [Task.Backgroundable], shows progress in the progress view and supports cancellation between
 * chunks.
 */
class CheckWorkspaceAction : AnAction() {
    /**
     * Enumerate the Ruby files, run `docscribe --format json` across the whole workspace
     * in chunks in a background task, and show a summary notification.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectDir = project.basePath ?: return
        val projectRoot =
            DocscribeRunner.findProjectRoot(projectDir) ?: run {
                notify(project, "No Gemfile found in project tree", NotificationType.ERROR)
                return
            }

        object : Task.Backgroundable(project, "DocScribe: checking workspace...", true) {
            var totalIssues = 0
            var totalErrors = 0
            var fileCount = 0
            var foundRubyFiles = true

            override fun run(indicator: ProgressIndicator) {
                val files = collectRubyFiles(project, projectRoot)
                if (files.isEmpty()) {
                    foundRubyFiles = false
                    return
                }
                try {
                    val summary =
                        runChunkedCheck(
                            files = files,
                            chunkSize = WORKSPACE_CHUNK_SIZE,
                            isCancelled = { indicator.isCanceled() },
                            checkCanceled = { indicator.checkCanceled() },
                            onProgress = { processed, total ->
                                indicator.fraction = processed.toDouble() / total
                                val from = processed + 1
                                val to = (processed + WORKSPACE_CHUNK_SIZE).coerceAtMost(total)
                                indicator.text = "DocScribe: checking files $from–$to of $total…"
                            },
                            executeChunk = { chunk ->
                                val result = DocscribeDaemon.executeBatchWithFallback(project, chunk, projectRoot)
                                if (result.exitCode >= 2) {
                                    throw WorkspaceCheckFailedException(
                                        result.stderr.ifBlank { "exit code ${result.exitCode}" },
                                    )
                                }
                                result
                            },
                        )
                    totalIssues = summary.issues
                    totalErrors = summary.errors
                    fileCount = summary.filesChecked
                } catch (e: WorkspaceCheckFailedException) {
                    notify(project, "DocScribe: error running docscribe: ${e.message}", NotificationType.ERROR)
                }
            }

            override fun onSuccess() {
                if (!foundRubyFiles) {
                    notify(project, "DocScribe: no Ruby files found in workspace", NotificationType.INFORMATION)
                    return
                }
                val msg = "DocScribe: checked $fileCount file(s)"
                val details = mutableListOf<String>()
                if (totalIssues > 0) details.add("$totalIssues issue(s) found")
                if (totalErrors > 0) details.add("$totalErrors error(s)")
                val text = if (details.isEmpty()) "$msg — OK" else "$msg — ${details.joinToString(", ")}"
                val type = if (totalIssues > 0) NotificationType.WARNING else NotificationType.INFORMATION
                notify(project, text, type)
            }
        }.queue()
    }

    /**
     * Enable the action only when a project is open.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    /**
     * Always use a background thread for update checks.
     */
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Show a DocScribe notification balloon.
     *
     * @param project The project to show the notification in.
     * @param content The notification message text.
     * @param type    The notification severity.
     */
    private fun notify(
        project: Project,
        content: String,
        type: NotificationType,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(content, type).notify(project)
    }

    companion object {
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
         * Run [executeChunk] over [files] in chunks of [chunkSize], reporting progress between
         * chunks and honouring cancellation.
         *
         * [checkCanceled] is invoked before every chunk; it is expected to throw a
         * `ProcessCanceledException` when the operation was cancelled. [isCancelled] is a
         * defensive non-throwing variant checked right after. Per-chunk JSON output is parsed
         * with [DocscribeOutputParser.parseJson] and the summaries are aggregated.
         *
         * @param files        Absolute paths of the Ruby files to check.
         * @param chunkSize    Maximum number of files per chunk.
         * @param isCancelled  Non-throwing cancellation probe.
         * @param checkCanceled Throwing cancellation probe, called before each chunk.
         * @param onProgress   Called with the number of files processed and the total.
         * @param executeChunk Runs the actual check for one chunk and returns its [RunResult].
         * @return Aggregated [ChunkedCheckSummary].
         */
        @JvmStatic
        fun runChunkedCheck(
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
                if (parsed != null) {
                    filesChecked += parsed.summary?.inspectedFileCount ?: 0
                    issues += parsed.summary?.offenseCount ?: 0
                    errors += parsed.summary?.errorCount ?: 0
                    processed += chunk.size
                } else {
                    processed += chunk.size
                }
            }
            return ChunkedCheckSummary(filesChecked, issues, errors)
        }

        /**
         * Thrown when a chunk fails with a fatal exit code (>= 2), aborting the workspace check.
         *
         * @property message Error detail to show to the user.
         */
        class WorkspaceCheckFailedException(
            message: String,
        ) : RuntimeException(message)

        /**
         * Collect absolute paths of all Ruby files in the project content roots.
         *
         * Only files under the project root directory are considered. Excluded files
         * (e.g. `.git`, `node_modules`, IDE exclusion patterns) are skipped, as are files
         * outside the project content roots.
         *
         * @param project    The current project.
         * @param projectRoot The project root directory (Gemfile location).
         * @return Absolute paths of the `.rb` / `.rake` / `Rakefile` files, in traversal order.
         */
        @JvmStatic
        fun collectRubyFiles(
            project: Project,
            projectRoot: String,
        ): List<String> {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val rootDir = LocalFileSystem.getInstance().findFileByIoFile(File(projectRoot)) ?: return emptyList()
            val collected = mutableListOf<String>()
            VfsUtilCore.iterateChildrenRecursively(
                rootDir,
                { f -> !fileIndex.isExcluded(f) },
            ) { f ->
                if (!f.isDirectory && isRubyFile(f.name) && fileIndex.isInContent(f)) {
                    collected.add(f.path)
                }
                true
            }
            return collected
        }

        /**
         * Whether a file name is a Ruby file handled by docscribe.
         *
         * @param name The file name (with extension).
         * @return `true` for `.rb`, `.rake`, and `Rakefile`.
         */
        @JvmStatic
        fun isRubyFile(name: String): Boolean = name.endsWith(".rb") || name.endsWith(".rake") || name == "Rakefile"
    }
}
