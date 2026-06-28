package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
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
 * Apply docscribe **aggressive** fixes (generate full YARD documentation) to the current Ruby file.
 *
 * Preserves existing manual descriptions via the `-k` flag.
 */
class AggressiveFixAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            FileDocumentManager.getInstance().saveDocument(editor.document)
        }

        val projectRoot =
            DocscribeRunner.findProjectRoot(vFile.path) ?: run {
                notify(project, "No Gemfile found in project tree", NotificationType.ERROR)
                return
            }

        object : Task.Backgroundable(project, "DocScribe: applying aggressive fix...", false) {
            var failed = false

            override fun run(indicator: ProgressIndicator) {
                val options =
                    RunOptions(
                        projectDir = projectRoot,
                        file = vFile.path,
                        strategy = DocscribeStrategy.AGGRESSIVE,
                        formatJson = false,
                    )
                val result = DocscribeDaemon.executeWithFallback(project, options)
                failed = result.exitCode >= 2
            }

            override fun onSuccess() {
                if (failed) {
                    notify(project, "DocScribe: error applying aggressive fix", NotificationType.ERROR)
                } else {
                    vFile.refresh(false, false)
                    FileDocumentManager.getInstance().reloadFiles(vFile)
                    notify(project, "DocScribe: aggressive fix applied", NotificationType.INFORMATION)
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.name.endsWith(".rb")
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
