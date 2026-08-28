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
    private val log =
        com.intellij.openapi.diagnostic.Logger
            .getInstance(DocscribeAnnotator::class.java)

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
        val settings = DocscribeSettings.getInstance()
        val configHash = Objects.hash(settings.hideCommentsByDefault, settings.warnOnInvalidYardTypes, projectDir.hashCode())
        // Use PsiFile stamp + document stamp for reliable change detection (VirtualFile stamp can be 0 for non-indexed files)
        val fileStamp = file.modificationStamp

        return AnnotatorFileInfo(
            filePath = vFile.path,
            projectDir = projectDir,
            fileStamp = fileStamp,
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

        val settings = DocscribeSettings.getInstance()
        val configHash = Objects.hash(settings.hideCommentsByDefault, settings.warnOnInvalidYardTypes, projectDir.hashCode())
        val fileStamp = file.modificationStamp

        return AnnotatorFileInfo(
            filePath = vFile.path,
            projectDir = projectDir,
            fileStamp = fileStamp,
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
    @Suppress("CyclomaticComplexMethod", "LongMethod", "DEPRECATION")
    override fun doAnnotate(info: AnnotatorFileInfo): DocscribeOutput? {
        val filePath = info.filePath
        log.info("DocScribe doAnnotate start file=$filePath stamp=${info.fileStamp} projectDir=${info.projectDir}")
        val generation = fileGeneration.merge(filePath, 1L) { _, old -> old + 1 }

        // Recompute configHash with RBS on background thread (not EDT) to include RBS state
        val settings = DocscribeSettings.getInstance()
        val bgConfigHash =
            Objects.hash(
                settings.hideCommentsByDefault,
                settings.warnOnInvalidYardTypes,
                RbsDetector.rbsHash(info.projectDir),
            )
        val effectiveHash = if (bgConfigHash != info.configHash) bgConfigHash else info.configHash
        log.info("DocScribe doAnnotate hashes infoHash=${info.configHash} bgHash=$bgConfigHash effective=$effectiveHash")

        val cache = DocscribeAnnotatorCache.getInstance()
        val cached = cache.get(info.projectDir, filePath, info.fileStamp, effectiveHash)
        log.info("DocScribe doAnnotate cache check cached=${cached != null} size=${cached?.files?.size}")
        if (cached != null) {
            log.info("DocScribe doAnnotate cache hit returning ${cached.files.size} files")
            return if (cached.files.isEmpty()) null else cached
        }

        // If document is unsaved, try to save it so daemon sees latest YARD invalid type.
        // Must use ReadAction for getDocument/isDocumentUnsaved (background thread has no read access).
        try {
            val shouldSave =
                com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                    val vFile =
                        com.intellij.openapi.vfs.LocalFileSystem
                            .getInstance()
                            .findFileByPath(filePath) ?: return@compute false
                    val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return@compute false
                    FileDocumentManager.getInstance().isDocumentUnsaved(doc)
                }
            if (shouldSave) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                    com.intellij.openapi.application.ReadAction.run<RuntimeException> {
                        val vFile =
                            com.intellij.openapi.vfs.LocalFileSystem
                                .getInstance()
                                .findFileByPath(filePath) ?: return@run
                        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return@run
                        if (FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
                            FileDocumentManager.getInstance().saveDocument(doc)
                        }
                    }
                }
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
        }

        log.info("DocScribe doAnnotate calling daemon for $filePath")
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
        val stderrPreview = result.stderr.take(MAX_STDERR_PREVIEW)
        log.info(
            "DocScribe doAnnotate daemon result success=${result.success} " +
                "exit=${result.exitCode} blank=${result.stdout.isBlank()} stderr=$stderrPreview",
        )

        // Another check for same file started while this one was running — discard
        if (fileGeneration[filePath] != generation) return null

        val output =
            when {
                !result.success -> null
                result.stdout.isBlank() -> DocscribeOutput(null, emptyList(), null)
                else -> DocscribeOutputParser.parseJson(result.stdout)
            }

        log.info("DocScribe doAnnotate parsed output files=${output?.files?.size} offenses=${output?.files?.firstOrNull()?.offenses?.size}")
        if (output != null) {
            cache.put(info.projectDir, filePath, info.fileStamp, effectiveHash, output)
        }
        val ret = if (output == null || output.files.isEmpty()) null else output
        log.info("DocScribe doAnnotate returning ${if (ret == null) "null" else "${ret.files.size} files"}")
        return ret
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
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    override fun apply(
        file: PsiFile,
        annotationResult: DocscribeOutput?,
        holder: AnnotationHolder,
    ) {
        val filePath = file.virtualFile?.path
        val offenseCount = annotationResult?.files?.sumOf { it.offenses.size }
        log.info("DocScribe apply file=$filePath result=${annotationResult?.files?.size} offenses=$offenseCount")
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        // 1. Daemon offenses (RBS mismatches, missing docs, invalid YARD)
        if (annotationResult != null) {
            for (parsedFile in annotationResult.files) {
                for (offense in parsedFile.offenses) {
                    val isRbsTypeUpdate = offense.copName == "Docscribe/UpdatedParam" || offense.copName == "Docscribe/UpdatedReturn"
                    val isInvalidYard = offense.copName == "Docscribe/InvalidType"
                    val baseLine = (offense.location.startLine - 1).coerceIn(0, document.lineCount - 1)
                    // For both RBS updates and invalid YARD, highlight the YARD comment, not the def
                    val line =
                        when {
                            isRbsTypeUpdate -> findYardTagLine(document, baseLine, offense.copName) ?: baseLine
                            isInvalidYard -> findYardTagLine(document, baseLine, offense.copName, offense.message) ?: baseLine
                            else -> baseLine
                        }
                    val lineStart = document.getLineStartOffset(line)
                    val lineEnd = document.getLineEndOffset(line)
                    val range = TextRange(lineStart, lineEnd)
                    val severity =
                        if (offense.severity == "fatal") {
                            HighlightSeverity.ERROR
                        } else {
                            HighlightSeverity.WARNING
                        }
                    // For RBS type mismatches, safe fix is no-op for existing @param,
                    // so offer update_types which does -AkB + -aB with rbs_collection.
                    // Keeps descriptions via -k. For invalid YARD, offer direct YARD fix.
                    val fix =
                        when {
                            isRbsTypeUpdate -> DocscribeUpdateTypesIntention()
                            isInvalidYard -> DocscribeInvalidYardTypeFixIntention(offense.message, line)
                            else -> DocscribeFixIntention()
                        }
                    holder
                        .newAnnotation(severity, offense.message)
                        .range(range)
                        .withFix(fix)
                        .create()
                }
            }
        }
        // YARD syntax validation without RBS is now handled by the gem via --validate-types
        // (Yard::Validator + TypeMismatchValidator) and appears as Docscribe/InvalidType above
    }

    private fun findYardTagLine(
        document: com.intellij.openapi.editor.Document,
        defLine: Int,
        copName: String,
    ): Int? {
        // For InvalidType, try to find the specific @param line if message contains "for @param"
        // otherwise fall back to generic @param/@return search
        return findYardTagLine(document, defLine, copName, null)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun findYardTagLine(
        document: com.intellij.openapi.editor.Document,
        defLine: Int,
        copName: String,
        message: String?,
    ): Int? {
        // Try to extract param name from message like "for @param args"
        val paramName = message?.let { Regex("""for @param (\w+)""").find(it)?.groupValues?.getOrNull(1) }
        var line = defLine - 1
        while (line >= 0) {
            val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
            val trimmed = text.trim()
            if (!trimmed.startsWith("#")) break
            if (paramName != null) {
                if (trimmed.contains("@param") && trimmed.contains(paramName)) return line
            } else {
                val tag = if (copName == "Docscribe/UpdatedParam") "@param" else "@return"
                if (trimmed.contains(tag)) return line
            }
            line--
        }
        // Fallback: search for line containing the invalid type text if paramName not found
        // paramName != null implies message != null (derived via message?.let), so !! is safe
        if (paramName != null) {
            val invalidType = Regex("""\[([^]]+)] for @param""").find(message)?.groupValues?.getOrNull(1)
            if (invalidType != null) {
                line = defLine - 1
                while (line >= 0) {
                    val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
                    if (text.contains("[$invalidType]")) return line
                    if (!text.trim().startsWith("#")) break
                    line--
                }
            }
        }
        return null
    }

    // noinspection CompanionObjectInExtension
    @Suppress("CompanionObjectInExtension")
    companion object {
        private const val MAX_STDERR_PREVIEW = 200

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
