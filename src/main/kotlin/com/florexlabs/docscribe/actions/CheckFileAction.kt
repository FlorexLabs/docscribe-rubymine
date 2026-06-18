package com.florexlabs.docscribe.actions

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
import com.intellij.openapi.project.Project

/**
 * Run docscribe **check** on the currently open Ruby file.
 *
 * Visible only for `.rb` files. Reports findings via a DocScribe notification balloon.
 */
class CheckFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val projectRoot =
            DocscribeRunner.findProjectRoot(vFile.path) ?: run {
                showError(project, "No Gemfile found in project tree")
                return
            }
        val options =
            RunOptions(
                projectDir = projectRoot,
                file = vFile.path,
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
            )
        val result = DocscribeRunner.runDocscribe(options)
        if (result.exitCode >= 2) {
            showError(project, "DocScribe: error running docscribe")
            return
        }
        val summary = buildSummary(result)
        notify(project, summary, if (result.hasIssues) NotificationType.WARNING else NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.name.endsWith(".rb")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

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

    private fun notify(
        project: Project,
        content: String,
        type: NotificationType,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(content, type).notify(project)
    }

    private fun showError(
        project: Project,
        message: String,
    ) {
        notify(project, message, NotificationType.ERROR)
    }
}
