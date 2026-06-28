package com.florexlabs.docscribe.folding

import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Folding builder that collapses YARD documentation comment blocks in Ruby files.
 *
 * Scans comment lines starting with `#`, detects YARD tags (`@param`, `@return`, etc.),
 * and creates folding regions for blocks that contain at least one YARD tag.
 * Collapsed-by-default state is driven by [DocscribeSettings.hideCommentsByDefault].
 *
 * Implements [DumbAware] to work without indexes, during indexing or otherwise.
 */
class YardFoldingBuilder :
    FoldingBuilderEx(),
    DumbAware {
    /**
     * Build folding regions for all YARD comment blocks in the file.
     *
     * Only processes `.rb` files. Skips comment blocks that do not contain any YARD tags.
     * Adjacent comment blocks are not merged — each block is a separate region.
     *
     * @param root     The root PSI element for the file.
     * @param document The document model.
     * @param quick    Whether this is a quick (incomplete) fold pass.
     * @return An array of [FoldingDescriptor] for YARD comment regions.
     */
    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val psiFile = root as? PsiFile
        if (psiFile == null || !psiFile.name.endsWith(".rb")) return emptyArray()

        val text = document.text
        val lines = text.lines()
        val regions = mutableListOf<FoldingDescriptor>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimStart()
            if (line.startsWith("#")) {
                val blockStart = i
                var hasYardTag = containsYardTag(line)
                i++
                while (i < lines.size) {
                    val next = lines[i].trimStart()
                    if (!next.startsWith("#")) break
                    if (containsYardTag(next)) hasYardTag = true
                    i++
                }
                val blockEnd = i - 1
                if (hasYardTag && blockEnd > blockStart) {
                    val startOffset = document.getLineStartOffset(blockStart)
                    val endOffset = document.getLineEndOffset(blockEnd)
                    val settings = DocscribeSettings.getInstance()
                    regions.add(
                        FoldingDescriptor(
                            root.node,
                            TextRange(startOffset, endOffset),
                            null,
                            null,
                            settings.hideCommentsByDefault,
                            emptySet(),
                        ),
                    )
                }
            } else {
                i++
            }
        }

        return regions.toTypedArray()
    }

    /**
     * The placeholder text shown for a folded YARD comment region.
     */
    @Suppress("NullableReturnType")
    override fun getPlaceholderText(node: ASTNode): String? = " // ..."

    /**
     * Whether YARD comment regions should be collapsed by default.
     *
     * Delegates to [DocscribeSettings.hideCommentsByDefault].
     */
    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        val settings = DocscribeSettings.getInstance()
        return settings.hideCommentsByDefault
    }
}

/**
 * Check whether a line contains any known YARD tag.
 *
 * Strips leading `#` and space characters, then checks for tag prefixes.
 * Recognises: @param, @return, @example, @raise, @see, @since, @version,
 * @yield, @option, @overload, @note, @todo, @deprecated, @abstract,
 * @attr_reader, @attr_writer, @attr_accessor.
 *
 * @param line A single line from the file (may include leading whitespace and `#`).
 * @return `true` if the line contains a recognised YARD tag.
 */
private fun containsYardTag(line: String): Boolean {
    val trimmed = line.trimStart('#', ' ').trimStart()
    return trimmed.startsWith("@param") ||
        trimmed.startsWith("@return") ||
        trimmed.startsWith("@example") ||
        trimmed.startsWith("@raise") ||
        trimmed.startsWith("@see") ||
        trimmed.startsWith("@since") ||
        trimmed.startsWith("@version") ||
        trimmed.startsWith("@yield") ||
        trimmed.startsWith("@option") ||
        trimmed.startsWith("@overload") ||
        trimmed.startsWith("@note") ||
        trimmed.startsWith("@todo") ||
        trimmed.startsWith("@deprecated") ||
        trimmed.startsWith("@abstract") ||
        trimmed.startsWith("@attr_reader") ||
        trimmed.startsWith("@attr_writer") ||
        trimmed.startsWith("@attr_accessor")
}
