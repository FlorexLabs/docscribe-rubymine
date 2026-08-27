package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile

/**
 * Quick-fix for RBS type mismatches — delegates to `docscribe update_types`
 * (two-pass: `-AkB --rbs-collection` + `-aB --rbs-collection`) which updates
 * `@param`/`@return` types from RBS while preserving descriptions via `-k`.
 * Shown for `Docscribe/UpdatedParam` / `UpdatedReturn` instead of safe fix,
 * because safe `-a -k -B --rbs` is a no-op for existing tags.
 */
class DocscribeUpdateTypesIntention : IntentionAction {
    override fun getText(): String = "DocScribe: Update types from RBS"

    override fun getFamilyName(): String = "DocScribe"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Boolean = file != null && (file.name.endsWith(".rb") || file.name.endsWith(".rake") || file.name == "Rakefile")

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
        object : Task.Backgroundable(project, "DocScribe: updating types from RBS...", false) {
            var failed = false
            var exitCode = -1
            override fun run(indicator: ProgressIndicator) {
                val options = RunOptions(projectDir = projectRoot, subcommand = "update_types")
                val result = DocscribeDaemon.executeWithFallback(project, options)
                failed = result.exitCode != 0
                exitCode = result.exitCode
                try {
                    val dirVFile = LocalFileSystem.getInstance().findFileByPath(projectRoot)
                    dirVFile?.refresh(true, true)
                } catch (_: Exception) {
                }
            }

            override fun onSuccess() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
                if (failed) {
                    group.createNotification("DocScribe: update_types failed (exit $exitCode)", NotificationType.ERROR).notify(project)
                } else {
                    vFile.refresh(false, false)
                    FileDocumentManager.getInstance().reloadFiles(vFile)
                    group.createNotification("DocScribe: types updated from RBS", NotificationType.INFORMATION).notify(project)
                }
            }
        }.queue()
    }

    override fun startInWriteAction(): Boolean = false
}
