package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeOutput
import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RunOptions
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Information collected by the annotator before running the background check.
 */
data class AnnotatorFileInfo(
    val filePath: String,
    val projectDir: String,
    val fileStamp: Long,
    val configHash: Int,
    val project: Project,
)

/**
 * [ExternalAnnotator] that runs docscribe **check** on Ruby files and shows inline diagnostics.
 *
 * Triggers automatically when a Ruby file is opened or saved. Uses JSON output for structured parsing.
 * Skips unsaved documents (docscribe reads from disk) and caches results by file modification stamp.
 */
class DocscribeAnnotator : ExternalAnnotator<AnnotatorFileInfo, DocscribeOutput>() {
    override fun collectInformation(
        file: PsiFile,
        editor: Editor,
        hasErrors: Boolean,
    ): AnnotatorFileInfo? {
        if (!file.name.endsWith(".rb") && !file.name.endsWith(".rake")) return null
        val vFile = file.virtualFile ?: return null
        val projectDir = file.project.basePath ?: return null

        // Skip unsaved documents — docscribe reads from disk, not from editor buffer
        if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)) return null

        val configHash = 0

        return AnnotatorFileInfo(
            filePath = vFile.path,
            projectDir = projectDir,
            fileStamp = vFile.modificationStamp,
            configHash = configHash,
            project = file.project,
        )
    }

    override fun collectInformation(file: PsiFile): AnnotatorFileInfo? {
        if (!file.name.endsWith(".rb") && !file.name.endsWith(".rake")) return null
        val vFile = file.virtualFile ?: return null
        val projectDir = file.project.basePath ?: return null

        val configHash = 0

        return AnnotatorFileInfo(
            filePath = vFile.path,
            projectDir = projectDir,
            fileStamp = vFile.modificationStamp,
            configHash = configHash,
            project = file.project,
        )
    }

    override fun doAnnotate(info: AnnotatorFileInfo): DocscribeOutput? {
        val cache = DocscribeAnnotatorCache.getInstance()
        val cached = cache.get(info.projectDir, info.filePath, info.fileStamp, info.configHash)
        if (cached != null) {
            return if (cached.files.isEmpty()) null else cached
        }

        val options =
            RunOptions(
                projectDir = info.projectDir,
                file = info.filePath,
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
            )
        val result = DocscribeDaemon.executeWithFallback(info.project, options)
        val output =
            when {
                !result.success -> null
                result.stdout.isBlank() -> DocscribeOutput(null, emptyList(), null)
                else -> DocscribeOutputParser.parseJson(result.stdout)
            }

        if (output != null) {
            cache.put(info.projectDir, info.filePath, info.fileStamp, info.configHash, output)
        }
        return if (output == null || output.files.isEmpty()) null else output
    }

    override fun apply(
        file: PsiFile,
        annotationResult: DocscribeOutput?,
        holder: AnnotationHolder,
    ) {
        if (annotationResult == null) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (parsedFile in annotationResult.files) {
            for (offense in parsedFile.offenses) {
                val line = (offense.location.startLine - 1).coerceIn(0, document.lineCount - 1)
                val lineStart = document.getLineStartOffset(line)
                val lineEnd = document.getLineEndOffset(line)
                val range = TextRange(lineStart, lineEnd)
                val severity =
                    if (offense.severity == "fatal") {
                        HighlightSeverity.ERROR
                    } else {
                        HighlightSeverity.WARNING
                    }
                holder
                    .newAnnotation(severity, offense.message)
                    .range(range)
                    .withFix(DocscribeFixIntention())
                    .create()
            }
        }
    }
}
