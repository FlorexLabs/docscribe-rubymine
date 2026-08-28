package com.florexlabs.docscribe.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Direct fix for invalid YARD types like `[Symb ol]` → `[Symbol]` without relying on `docscribe safe`.
 * Parses the expected type from the offense message `invalid YARD type [Symb ol] for @return, expected [Symbol]`
 * and replaces the YARD type in the comment.
 */
class DocscribeInvalidYardTypeFixIntention(
    private val message: String,
    private val line: Int? = null,
) : IntentionAction {
    override fun getText(): String {
        val expected = extractExpectedType(message) ?: extractInvalidType(message)?.let { suggestFix(it) }
        return if (expected != null && expected != extractInvalidType(message)) "Fix YARD type → [$expected]" else "Fix invalid YARD type"
    }

    override fun getFamilyName(): String = "DocScribe"

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Boolean = file != null && editor != null

    @Suppress("CyclomaticComplexMethod", "MagicNumber", "ReturnCount")
    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        if (editor == null || file == null) return
        val document = editor.document
        val targetLine =
            line ?: run {
                val caretLine = document.getLineNumber(editor.caretModel.offset)
                caretLine
            }
        if (targetLine < 0 || targetLine >= document.lineCount) return
        val lineStart = document.getLineStartOffset(targetLine)
        val lineEnd = document.getLineEndOffset(targetLine)
        val lineText = document.getText(TextRange(lineStart, lineEnd))
        // Also try to find the YARD line if caret is on def
        var searchLine = targetLine
        var searchText = lineText
        if (!searchText.contains("@return") && !searchText.contains("@param")) {
            // Try to find YARD tag line above def
            for (i in targetLine - 1 downTo maxOf(0, targetLine - 5)) {
                val t = document.getText(TextRange(document.getLineStartOffset(i), document.getLineEndOffset(i)))
                if (t.contains("@return") || t.contains("@param")) {
                    searchLine = i
                    searchText = t
                    break
                }
            }
        } else {
            searchLine = targetLine
            searchText = lineText
        }
        val invalid = extractInvalidType(message) ?: return
        val expected = extractExpectedType(message) ?: suggestFix(invalid) ?: return
        if (invalid == expected) return
        val newLineText = searchText.replace("[$invalid]", "[$expected]")
        if (newLineText == searchText) return
        val s = document.getLineStartOffset(searchLine)
        val e = document.getLineEndOffset(searchLine)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(s, e, newLineText.trimEnd('\n') + "\n".let { if (newLineText.endsWith("\n")) "" else "" })
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    override fun startInWriteAction(): Boolean = false

    companion object {
        private val KNOWN_TYPES =
            listOf(
                "Object",
                "String",
                "Integer",
                "Float",
                "Symbol",
                "Array",
                "Hash",
                "Boolean",
                "TrueClass",
                "FalseClass",
                "NilClass",
                "Numeric",
                "Proc",
                "Range",
                "Regexp",
                "IO",
                "File",
                "Time",
                "Date",
                "void",
                "nil",
                "self",
                "untyped",
                "bool",
            )

        fun extractInvalidType(msg: String): String? {
            val m = Regex("""\[([^]]+)] for @""").find(msg) ?: Regex("""\[([^]]+)]""").find(msg)
            return m?.groupValues?.getOrNull(1)
        }

        fun extractExpectedType(msg: String): String? {
            val m = Regex("""expected \[([^]]+)]""").find(msg)
            return m?.groupValues?.getOrNull(1)
        }

        fun suggestFix(type: String): String? {
            var cleaned = type.filter { it.code <= 127 }.filterNot { it.isDigit() }.replace(" ", "")
            cleaned = cleaned.replace("к", "o").replace("К", "O")
            if (cleaned.isEmpty()) return null
            if (cleaned in KNOWN_TYPES) return cleaned
            if ('<' in cleaned || ',' in cleaned) {
                // For generic types like Arraаy<Object>, clean inner and return
                // Arraаy<Object> -> Array<Object> after filtering
                return cleaned
            }
            var best: String? = null
            var bestDist = Int.MAX_VALUE
            for (known in KNOWN_TYPES) {
                val dist = levenshtein(cleaned, known)
                if (dist < bestDist) {
                    bestDist = dist
                    best = known
                }
            }
            return if (best != null && bestDist <= 2) best else cleaned
        }

        private fun levenshtein(
            a: String,
            b: String,
        ): Int {
            val m = a.length
            val n = b.length
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 0..m) dp[i][0] = i
            for (j in 0..n) dp[0][j] = j
            for (i in 1..m) {
                for (j in 1..n) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                }
            }
            return dp[m][n]
        }
    }
}
