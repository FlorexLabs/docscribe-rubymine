package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Run docscribe **check** on all Ruby files in the project workspace.
 *
 * Runs in background via [Task.Backgroundable]. Shows a summary notification when done.
 */
class CheckWorkspaceAction : AnAction() {
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

            override fun run(indicator: ProgressIndicator) {
                val options =
                    RunOptions(
                        projectDir = projectRoot,
                        strategy = DocscribeStrategy.CHECK,
                        formatJson = true,
                    )
                val result = DocscribeRunner.runDocscribe(options)
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

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun notify(
        project: Project,
        content: String,
        type: NotificationType,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(content, type).notify(project)
    }
}
