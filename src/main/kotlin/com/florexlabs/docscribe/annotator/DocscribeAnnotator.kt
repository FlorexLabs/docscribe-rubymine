package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

data class AnnotatorFileInfo(
    val filePath: String,
    val projectDir: String
)

class DocscribeAnnotator : ExternalAnnotator<AnnotatorFileInfo, com.florexlabs.docscribe.runner.DocscribeOutput>() {

    override fun collectInformation(file: PsiFile): AnnotatorFileInfo? {
        if (!file.name.endsWith(".rb")) return null
        val vFile = file.virtualFile ?: return null
        val projectDir = file.project.basePath ?: return null
        return AnnotatorFileInfo(vFile.path, projectDir)
    }

    override fun doAnnotate(info: AnnotatorFileInfo): com.florexlabs.docscribe.runner.DocscribeOutput? {
        val projectRoot = DocscribeRunner.findProjectRoot(info.filePath) ?: return null
        val options = RunOptions(
            projectDir = projectRoot,
            file = info.filePath,
            strategy = DocscribeStrategy.CHECK,
            formatJson = true
        )
        val result = DocscribeRunner.runDocscribe(options)
        if (!result.success || result.stdout.isBlank()) return null
        return DocscribeOutputParser.parseJson(result.stdout)
    }

    override fun apply(
        file: PsiFile,
        annotationResult: com.florexlabs.docscribe.runner.DocscribeOutput?,
        holder: AnnotationHolder
    ) {
        if (annotationResult == null) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (parsedFile in annotationResult.files) {
            for (offense in parsedFile.offenses) {
                val line = (offense.location.startLine - 1).coerceIn(0, document.lineCount - 1)
                val lineStart = document.getLineStartOffset(line)
                val lineEnd = document.getLineEndOffset(line)
                val range = TextRange(lineStart, lineEnd)
                val severity = if (offense.severity == "fatal") {
                    HighlightSeverity.ERROR
                } else {
                    HighlightSeverity.WARNING
                }
                holder.newAnnotation(severity, offense.message)
                    .range(range)
                    .withFix(DocscribeFixIntention())
                    .create()
            }
        }
    }
}
