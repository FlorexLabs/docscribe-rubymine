package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeOutput
import com.florexlabs.docscribe.runner.DocscribeOutputParser
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.runner.RbsDetector
import com.florexlabs.docscribe.runner.RunOptions
import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.VisibleForTesting
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap

/**
 * Information collected by the annotator before running the background check.
 *
 * @property configHash Hash of [DocscribeSettings] plus RBS file states used as part of the cache key.
 *   When settings or RBS signatures change, the hash changes, causing cache misses and forcing re-annotation.
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
 *
 * ## Concurrency
 * When the IDE triggers multiple rapid annotations for the same file (e.g. during a series of quick saves),
 * only the last check result is applied. Each file has a generation counter — if a newer check starts
 * while an older one is still running, the older result is discarded on completion.
 *
 * ## Cache invalidation
 * The [AnnotatorFileInfo.configHash] is derived from [DocscribeSettings] plus RBS file states,
 * so changing settings or RBS signatures automatically invalidates cached annotations.
 * The DocscribeSettingsChangeListener also clears the cache explicitly on settings change.
 */
class DocscribeAnnotator : ExternalAnnotator<AnnotatorFileInfo, DocscribeOutput>() {
    /**
     * Collect file information for annotation when an editor is available.
     *
     * Skips non-Ruby/Rake files and unsaved documents (docscribe reads from disk, not the editor buffer).
     *
     * @param file      The PSI file being annotated.
     * @param editor    The editor for the file.
     * @param hasErrors Whether the file already has parse errors.
     * @return [AnnotatorFileInfo] if the file should be checked, or `null` to skip.
     */
    override fun collectInformation(
        file: PsiFile,
        editor: Editor,
        hasErrors: Boolean,
    ): AnnotatorFileInfo? {
        if (!file.name.endsWith(".rb") && !file.name.endsWith(".rake") && file.name != "Rakefile") return null
        val vFile = file.virtualFile ?: return null
        val projectDir = file.project.basePath ?: return null

        // For RBS projects, don't skip unsaved documents — the daemon can handle the
        // current editor content via the file on disk after an auto-save, and the
        // delay the user sees is often just waiting for the next save. We still
        // return info for unsaved docs so the annotator runs on the next background
        // pass after save, but we don't block the EDT with heavy I/O.
        if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document) && !RbsDetector.shouldUseRbs(projectDir)) return null

        // Use a fast hash for EDT — rbsHash does file I/O, so we cache it and only recompute in doAnnotate
        val configHash = Objects.hash(DocscribeSettings.getInstance().hideCommentsByDefault, projectDir.hashCode())

        return AnnotatorFileInfo(
            filePath = vFile.path,
            projectDir = projectDir,
            fileStamp = vFile.modificationStamp,
            configHash = configHash,
            project = file.project,
        )
    }

    /**
     * Collect file information for annotation when no editor is available (background re-annotation).
     *
     * Skips non-Ruby/Rake files.
     *
     * @param file The PSI file being annotated.
     * @return [AnnotatorFileInfo] if the file should be checked, or `null` to skip.
     */
    override fun collectInformation(file: PsiFile): AnnotatorFileInfo? {
        if (!file.name.endsWith(".rb") && !file.name.endsWith(".rake") && file.name != "Rakefile") return null
        val vFile = file.virtualFile ?: return null
        val projectDir = file.project.basePath ?: return null

        val configHash = Objects.hash(DocscribeSettings.getInstance().hideCommentsByDefault, projectDir.hashCode())

        return AnnotatorFileInfo(
            filePath = vFile.path,
            projectDir = projectDir,
            fileStamp = vFile.modificationStamp,
            configHash = configHash,
            project = file.project,
        )
    }

    /**
     * Run docscribe check on the collected file, using the cache if the file is unchanged.
     *
     * Each file has a generation counter. When a new check starts for a file, the counter is
     * incremented. If an older check completes and finds its generation is stale (a newer check
     * already started), the result is discarded. This ensures that during rapid saves only the
     * last check result is applied.
     *
     * @param info The file information collected by [collectInformation].
     * @return Parsed [DocscribeOutput], or `null` if the file has no issues or the check failed.
     */
    override fun doAnnotate(info: AnnotatorFileInfo): DocscribeOutput? {
        val filePath = info.filePath
        val generation = fileGeneration.merge(filePath, 1L) { _, old -> old + 1 }

        // Recompute configHash with RBS on background thread (not EDT) to include RBS state
        val bgConfigHash = Objects.hash(DocscribeSettings.getInstance().hideCommentsByDefault, RbsDetector.rbsHash(info.projectDir))
        val effectiveHash = if (bgConfigHash != info.configHash) bgConfigHash else info.configHash

        val cache = DocscribeAnnotatorCache.getInstance()
        val cached = cache.get(info.projectDir, filePath, info.fileStamp, effectiveHash)
        if (cached != null) {
            return if (cached.files.isEmpty()) null else cached
        }

        // For RBS projects, the check must include RBS types; ensure the daemon is warmed up
        // in the background to reduce perceived delay on first edit after save.
        val options =
            RunOptions(
                projectDir = info.projectDir,
                file = filePath,
                strategy = DocscribeStrategy.CHECK,
                formatJson = true,
            )
        val result = DocscribeDaemon.executeWithFallback(info.project, options)

        // Another check for the same file started while this one was running — discard
        if (fileGeneration[filePath] != generation) return null

        val output =
            when {
                !result.success -> null
                result.stdout.isBlank() -> DocscribeOutput(null, emptyList(), null)
                else -> DocscribeOutputParser.parseJson(result.stdout)
            }

        if (output != null) {
            cache.put(info.projectDir, filePath, info.fileStamp, effectiveHash, output)
        }
        return if (output == null || output.files.isEmpty()) null else output
    }

    /**
     * Apply inline annotations (squigglies) based on the docscribe output.
     *
     * Maps each offense to a warning (or error for severity `"fatal"`) on the offending line,
     * with a quick-fix attached via [DocscribeFixIntention].
     *
     * @param file             The PSI file being annotated.
     * @param annotationResult The parsed docscribe output, or `null`.
     * @param holder           The annotation holder to add annotations to.
     */
    override fun apply(
        file: PsiFile,
        annotationResult: DocscribeOutput?,
        holder: AnnotationHolder,
    ) {
        if (annotationResult == null) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (parsedFile in annotationResult.files) {
            for (offense in parsedFile.offenses) {
                val isRbsTypeUpdate = offense.copName == "Docscribe/UpdatedParam" || offense.copName == "Docscribe/UpdatedReturn"
                val baseLine = (offense.location.startLine - 1).coerceIn(0, document.lineCount - 1)
                val line = if (isRbsTypeUpdate) findYardTagLine(document, baseLine, offense.copName) ?: baseLine else baseLine
                val lineStart = document.getLineStartOffset(line)
                val lineEnd = document.getLineEndOffset(line)
                val range = TextRange(lineStart, lineEnd)
                val severity =
                    if (offense.severity == "fatal") {
                        HighlightSeverity.ERROR
                    } else {
                        HighlightSeverity.WARNING
                    }
                // For RBS type mismatches, safe fix (-a -k -B --rbs) is a no-op for existing @param (only adds missing),
                // so we must offer update_types (docscribe update_types) which does -AkB + -aB with rbs_collection and keeps descriptions via -k.
                // Use a dedicated intention that delegates to update_types for RBS.
                val fix =
                    if (isRbsTypeUpdate) {
                        DocscribeUpdateTypesIntention()
                    } else {
                        DocscribeFixIntention()
                    }
                holder
                    .newAnnotation(severity, offense.message)
                    .range(range)
                    .withFix(fix)
                    .create()
            }
        }
    }

    private fun findYardTagLine(
        document: com.intellij.openapi.editor.Document,
        defLine: Int,
        copName: String,
    ): Int? {
        val tag = if (copName == "Docscribe/UpdatedParam") "@param" else "@return"
        var line = defLine - 1
        while (line >= 0) {
            val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
            val trimmed = text.trim()
            if (!trimmed.startsWith("#")) break
            if (trimmed.contains(tag)) return line
            line--
        }
        return null
    }

    // noinspection CompanionObjectInExtension
    @Suppress("CompanionObjectInExtension")
    companion object {
        /**
         * Generation counter per file path.
         *
         * Incremented each time [doAnnotate] starts for a file. When an annotation completes,
         * its generation is compared to the current value — if they differ, a newer check ran
         * and the stale result is discarded (see [doAnnotate]).
         */
        @VisibleForTesting
        internal val fileGeneration = ConcurrentHashMap<String, Long>()
    }
}
