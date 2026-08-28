package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.annotator.DocscribeAnnotatorCache
import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.RbsDetector
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Run `docscribe update_types` to refresh YARD documentation from RBS signatures.
 *
 * Only visible when the project's Gemfile contains the `rbs` gem.
 */
class UpdateTypesAction : AnAction() {
    /**
     * Find the project root, check that the Gemfile contains `rbs`, and run
     * `docscribe update_types` in a background task. Show a notification on completion.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectDir = project.basePath ?: return
        val projectRoot =
            DocscribeRunner.findProjectRoot(projectDir) ?: run {
                notify(project, "No Gemfile found in project tree", NotificationType.ERROR)
                return
            }
        if (!RbsDetector.shouldUseRbs(projectRoot)) {
            notify(project, "RBS not found — update_types requires RBS (sig/ or rbs gem)", NotificationType.WARNING)
            return
        }

        object : Task.Backgroundable(project, "DocScribe: updating types from RBS...", false) {
            var exitCode = -1
            var stderrText = ""

            override fun run(indicator: ProgressIndicator) {
                val options =
                    RunOptions(
                        projectDir = projectRoot,
                        subcommand = "update_types",
                    )
                val result = DocscribeDaemon.executeWithFallback(project, options)
                exitCode = result.exitCode
                stderrText = result.stderr
                // Clear annotator cache — files on disk changed, cached check results are stale
                try {
                    DocscribeAnnotatorCache.getInstance().clear()
                } catch (_: Exception) {
                }
                // Refresh VFS so the editor shows the updated YARD docs
                try {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(projectRoot)
                    vFile?.refresh(true, true)
                } catch (_: Exception) {
                }
                // Also reload open documents
                try {
                    val mgr = FileDocumentManager.getInstance()
                    for (file in com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles) {
                        mgr.reloadFiles(file)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onSuccess() {
                if (exitCode == 0) {
                    notify(project, "DocScribe: types updated successfully", NotificationType.INFORMATION)
                } else {
                    val msg = if (stderrText.isNotBlank()) stderrText.take(500) else "exit code $exitCode"
                    notify(project, "DocScribe: update_types failed: $msg", NotificationType.WARNING)
                }
            }

            override fun onThrowable(error: Throwable) {
                notify(project, "DocScribe: update_types error: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val project =
            e.project ?: run {
                e.presentation.isEnabledAndVisible = false
                return
            }
        val projectDir =
            project.basePath ?: run {
                e.presentation.isEnabledAndVisible = false
                return
            }
        val projectRoot = DocscribeRunner.findProjectRoot(projectDir)
        if (projectRoot == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // Visible when RBS is available (sig/ or Gemfile.lock or docscribe.yml), not just Gemfile declaration.
        // This matches RbsDetector.shouldUseRbs used by annotator/daemon, so button is not hidden
        // when sig/ exists but Gemfile doesn't directly declare `gem "rbs"` (e.g. transitive).
        e.presentation.isEnabledAndVisible = RbsDetector.shouldUseRbs(projectRoot)
        e.presentation.isEnabled = true
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
