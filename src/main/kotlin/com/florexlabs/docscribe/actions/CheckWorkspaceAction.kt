package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

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
}
