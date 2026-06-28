package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
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
 * Intention action (lightbulb) that applies an **aggressive** docscribe fix to the annotated file.
 *
 * Appears as "DocScribe: Apply aggressive fix" in the Alt+Enter quick-fix menu.
 * Generates full YARD documentation via docscribe's `-A -k -B` flags, preserving existing manual descriptions.
 */
class DocscribeAggressiveFixIntention : IntentionAction {
    /**
     * The display text shown in the Alt+Enter intention menu.
     */
    override fun getText(): String = "DocScribe: Apply aggressive fix"

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
     * Run docscribe aggressive fix in a background task, then refresh the file on success.
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
                failed = result.exitCode != 0
            }

            override fun onSuccess() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
                if (failed) {
                    group
                        .createNotification("DocScribe: error applying aggressive fix", NotificationType.ERROR)
                        .notify(project)
                } else {
                    vFile.refresh(false, false)
                    FileDocumentManager.getInstance().reloadFiles(vFile)
                    group
                        .createNotification("DocScribe: aggressive fix applied", NotificationType.INFORMATION)
                        .notify(project)
                }
            }
        }.queue()
    }

    /**
     * This intention does not modify the PSI directly, so it runs outside a write action.
     */
    override fun startInWriteAction(): Boolean = false
}
