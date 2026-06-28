package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

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
        if (!gemfileHasRbs("$projectRoot/Gemfile")) {
            notify(project, "RBS not found in Gemfile — update_types requires RBS", NotificationType.WARNING)
            return
        }

        object : Task.Backgroundable(project, "DocScribe: updating types from RBS...", false) {
            var exitCode = -1

            override fun run(indicator: ProgressIndicator) {
                val options =
                    RunOptions(
                        projectDir = projectRoot,
                        subcommand = "update_types",
                    )
                val result = DocscribeDaemon.executeWithFallback(project, options)
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
        e.presentation.isEnabledAndVisible = gemfileHasRbs("$projectRoot/Gemfile")
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

    /**
     * Check whether the Gemfile at [gemfilePath] contains the `rbs` gem.
     *
     * @param gemfilePath Absolute path to the Gemfile.
     * @return `true` if `gem "rbs"` is declared in the Gemfile.
     */
    private fun gemfileHasRbs(gemfilePath: String): Boolean =
        try {
            val content = File(gemfilePath).readText()
            Regex("""gem\s+['"]rbs['"]""").containsMatchIn(content)
        } catch (_: Exception) {
            false
        }
}
