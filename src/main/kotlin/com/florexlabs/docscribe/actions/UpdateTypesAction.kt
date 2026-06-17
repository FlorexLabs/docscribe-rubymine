package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

class UpdateTypesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectDir = project.basePath ?: return
        val projectRoot = DocscribeRunner.findProjectRoot(projectDir) ?: run {
            notify(project, "No Gemfile found in project tree", NotificationType.ERROR)
            return
        }
        if (!DocscribeRunner.gemfileHasRbs("$projectRoot/Gemfile")) {
            notify(project, "RBS not found in Gemfile — update_types requires RBS", NotificationType.WARNING)
            return
        }

        object : Task.Backgroundable(project, "DocScribe: updating types from RBS...", false) {
            var exitCode = -1

            override fun run(indicator: ProgressIndicator) {
                val options = RunOptions(
                    projectDir = projectRoot,
                    subcommand = "update_types"
                )
                val result = DocscribeRunner.runDocscribe(options)
                exitCode = result.exitCode
            }

            override fun onSuccess() {
                if (exitCode == 0) {
                    notify(project, "DocScribe: types updated successfully", NotificationType.INFORMATION)
                } else {
                    notify(project, "DocScribe: update_types finished with exit code $exitCode", NotificationType.WARNING)
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val projectDir = project.basePath ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val projectRoot = DocscribeRunner.findProjectRoot(projectDir)
        if (projectRoot == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = DocscribeRunner.gemfileHasRbs("$projectRoot/Gemfile")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun notify(project: com.intellij.openapi.project.Project, content: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(content, type).notify(project)
    }
}
