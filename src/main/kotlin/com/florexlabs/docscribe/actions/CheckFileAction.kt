package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Run docscribe **check** on the current Ruby file.
 *
 * Triggered via menu (Editor Popup -> DocScribe -> Check Current File) or keyboard shortcut
 * (Ctrl+Shift+D, then C). Shows a notification with the check summary.
 */
class CheckFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            FileDocumentManager.getInstance().saveDocument(editor.document)
        }

        val projectRoot =
            DocscribeRunner.findProjectRoot(vFile.path) ?: run {
                showError(project, "No Gemfile found in project tree")
                return
            }

        object : Task.Backgroundable(project, "DocScribe: checking file...", false) {
            var result: RunResult? = null

            override fun run(indicator: ProgressIndicator) {
                val options =
                    RunOptions(
                        projectDir = projectRoot,
                        file = vFile.path,
                        strategy = DocscribeStrategy.CHECK,
                        formatJson = true,
                    )
                result = DocscribeDaemon.executeWithFallback(project, options)
            }

            override fun onSuccess() {
                val r = result ?: return
                if (r.exitCode >= 2) {
                    showError(project, "DocScribe: error running docscribe")
                    return
                }
                val summary = buildSummary(r)
                notify(project, summary, if (r.hasIssues) NotificationType.WARNING else NotificationType.INFORMATION)
            }
        }.queue()
    }

    /**
     * Enable the action only when a `.rb` file is selected.
     */
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.name.endsWith(".rb")
    }

    /**
     * Always use a background thread for update checks.
     */
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Build a human-readable summary string from the docscribe JSON output.
     *
     * @param result The run result from docscribe.
     * @return A formatted summary string, e.g. "DocScribe: checked 1 file(s) — 2 issue(s) found".
     */
    private fun buildSummary(result: RunResult): String {
        val parsed = DocscribeOutputParser.parseJson(result.stdout)
        if (parsed != null) {
            val s = parsed.summary
            if (s != null) {
                val parts = mutableListOf("DocScribe: checked ${s.inspectedFileCount} file(s)")
                if (s.offenseCount > 0) parts.add("${s.offenseCount} issue(s) found")
                if (s.errorCount > 0) parts.add("${s.errorCount} error(s)")
                return parts.joinToString(" — ")
            }
        }
        return if (result.hasIssues) "DocScribe: issues found" else "DocScribe: OK"
    }

    /**
     * Show a DocScribe notification balloon.
     *
     * @param project The project to show the notification in.
     * @param content The notification message text.
     * @param type    The notification severity (ERROR, WARNING, INFORMATION).
     */
    private fun notify(
        project: Project,
        content: String,
        type: NotificationType,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(content, type).notify(project)
    }

    /**
     * Show an error notification.
     *
     * @param project The project to show the notification in.
     * @param message The error message to display.
     */
    private fun showError(
        project: Project,
        message: String,
    ) {
        notify(project, message, NotificationType.ERROR)
    }
}
