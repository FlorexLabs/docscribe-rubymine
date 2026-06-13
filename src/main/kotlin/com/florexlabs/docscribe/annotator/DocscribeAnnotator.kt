package com.florexlabs.docscribe.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.psi.PsiFile

class DocscribeAnnotator : ExternalAnnotator<PsiFile, Unit>() {
    override fun collectInformation(file: PsiFile): PsiFile = file

    override fun doAnnotate(collectedInfo: PsiFile) {}

    override fun apply(file: PsiFile, annotationResult: Unit, holder: AnnotationHolder) {}
}
