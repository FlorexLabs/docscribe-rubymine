package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
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
 * to the daemon in a single `check_batch` RPC call (falling back to the CLI directory scan
 * when the daemon or batch support is unavailable). Runs in background via
 * [Task.Backgroundable] and shows a summary notification when done.
 */
class CheckWorkspaceAction : AnAction() {
    /**
     * Enumerate the Ruby files, run `docscribe --format json` across the whole workspace
     * in a background task, and show a summary notification.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectDir = project.basePath ?: return
        val projectRoot =
            DocscribeRunner.findProjectRoot(projectDir) ?: run {
                notify(project, "No Gemfile found in project tree", NotificationType.ERROR)
                return
            }

        object : Task.Backgroundable(project, "DocScribe: checking workspace...", false) {
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
                val result = DocscribeDaemon.executeBatchWithFallback(project, files, projectRoot)
                if (result.exitCode >= 2) {
                    notify(project, "DocScribe: error running docscribe", NotificationType.ERROR)
                    return
                }
                val parsed = DocscribeOutputParser.parseJson(result.stdout)
                if (parsed != null) {
                    totalIssues = parsed.summary?.offenseCount ?: 0
                    totalErrors = parsed.summary?.errorCount ?: 0
                    fileCount = parsed.summary?.inspectedFileCount ?: 0
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
