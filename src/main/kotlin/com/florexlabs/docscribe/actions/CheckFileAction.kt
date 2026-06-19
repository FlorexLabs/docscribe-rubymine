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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

class CheckFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
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
                result = DocscribeRunner.runDocscribe(options)
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
