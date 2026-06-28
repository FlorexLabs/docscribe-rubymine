package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.florexlabs.docscribe.runner.RunResult
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Intention action (lightbulb) that runs docscribe **check** on the current Ruby file.
 *
 * Appears as "DocScribe: Check current file" in the Alt+Enter quick-fix menu.
 * Runs docscribe in JSON mode and shows a notification with the result summary.
 */
class DocscribeCheckIntention : IntentionAction {
    /**
     * The display text shown in the Alt+Enter intention menu.
     */
    override fun getText(): String = "DocScribe: Check current file"

    /**
     * The family name groups related intentions together in settings.
     */
    override fun getFamilyName(): String = "DocScribe"

    /**
     * Available only for `.rb` files.
     */
    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Boolean = file != null && file.name.endsWith(".rb")

    /**
     * Run docscribe check in a background task and show a notification on completion.
     */
    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        val psiFile = file ?: return
        val vFile = psiFile.virtualFile ?: return

        if (editor != null) {
            FileDocumentManager.getInstance().saveDocument(editor.document)
        }

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
                result = DocscribeDaemon.executeWithFallback(project, options)
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

    /**
     * This intention does not modify the PSI directly, so it runs outside a write action.
     */
    override fun startInWriteAction(): Boolean = false

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
}
