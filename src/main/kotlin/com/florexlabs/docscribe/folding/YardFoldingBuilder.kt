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

class YardFoldingBuilder :
    FoldingBuilderEx(),
    DumbAware {
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
                    regions.add(FoldingDescriptor(root.node, TextRange(startOffset, endOffset)))
                }
            } else {
                i++
            }
        }

        return regions.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? = " // ..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        val settings = DocscribeSettings.getInstance()
        return settings.hideCommentsByDefault
    }
}

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
