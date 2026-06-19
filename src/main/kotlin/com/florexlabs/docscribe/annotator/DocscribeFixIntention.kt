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
 * Quick-fix intention that applies a **safe** docscribe fix to the annotated file.
 *
 * Appears as a lightbulb action on inline annotations from [DocscribeAnnotator].
 */
class DocscribeFixIntention : IntentionAction {
    override fun getText(): String = "DocScribe: Apply safe fix"

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

        // Save editor buffer to disk so docscribe reads the latest version
        if (editor != null) {
            FileDocumentManager.getInstance().saveDocument(editor.document)
        }

        val projectRoot = DocscribeRunner.findProjectRoot(vFile.path) ?: return

        object : Task.Backgroundable(project, "DocScribe: applying fix...", false) {
            var failed = false

            override fun run(indicator: ProgressIndicator) {
                val options =
                    RunOptions(
                        projectDir = projectRoot,
                        file = vFile.path,
                        strategy = DocscribeStrategy.SAFE,
                        formatJson = false,
                    )
                val result = DocscribeDaemon.executeWithFallback(project, options)
                failed = result.exitCode >= 2
            }

            override fun onSuccess() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
                if (failed) {
                    group
                        .createNotification("DocScribe: failed to apply fix", NotificationType.ERROR)
                        .notify(project)
                } else {
                    FileDocumentManager.getInstance().reloadFiles(vFile)
                    group
                        .createNotification("DocScribe: fix applied", NotificationType.INFORMATION)
                        .notify(project)
                }
            }
        }.queue()
    }

    override fun startInWriteAction(): Boolean = false
}
