package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class DocscribeFixIntention : IntentionAction {
    override fun getText(): String = "Apply docscribe fix"

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
        val options =
            RunOptions(
                projectDir = projectRoot,
                file = vFile.path,
                strategy = DocscribeStrategy.SAFE,
                formatJson = false,
            )
        val result = DocscribeRunner.runDocscribe(options)
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        if (result.exitCode >= 2) {
            group
                .createNotification("DocScribe: failed to apply fix", NotificationType.ERROR)
                .notify(project)
        } else {
            group
                .createNotification("DocScribe: fix applied", NotificationType.INFORMATION)
                .notify(project)
        }
    }

    override fun startInWriteAction(): Boolean = true
}
