package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class DocscribeCheckIntention : IntentionAction {
    override fun getText(): String = "DocScribe: Check current file"

    override fun getFamilyName(): String = "DocScribe"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Boolean = file != null && file.name.endsWith(".rb")

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        val psiFile = file ?: return
        val vFile = psiFile.virtualFile ?: return
        val projectRoot = DocscribeRunner.findProjectRoot(vFile.path) ?: return

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
                val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
                if (r.exitCode >= 2) {
                    group
                        .createNotification("DocScribe: error running docscribe", NotificationType.ERROR)
                        .notify(project)
                    return
                }
                val summary = buildSummary(r)
                group
                    .createNotification(summary, if (r.hasIssues) NotificationType.WARNING else NotificationType.INFORMATION)
                    .notify(project)
            }
        }.queue()
    }

    override fun startInWriteAction(): Boolean = false

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
}
