package com.florexlabs.docscribe.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class DocscribeFixIntention : IntentionAction {
    override fun getText(): String = "Apply docscribe fix"
    override fun getFamilyName(): String = "DocScribe"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {}
    override fun startInWriteAction(): Boolean = true
}
