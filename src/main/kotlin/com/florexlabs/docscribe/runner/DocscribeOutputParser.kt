package com.florexlabs.docscribe.runner

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * Location range of a single offense within a source file.
 */
data class OffenseLocation(
    @SerializedName("start_line") val startLine: Int,
    @SerializedName("start_column") val startColumn: Int,
    @SerializedName("last_line") val lastLine: Int,
    @SerializedName("last_column") val lastColumn: Int,
)

/**
 * A single diagnostic finding from docscribe.
 */
data class ParsedOffense(
    val severity: String,
    @SerializedName("cop_name") val copName: String,
    val message: String,
    val corrected: Boolean,
    val correctable: Boolean,
    val location: OffenseLocation,
)

/**
 * A file with its list of offenses.
 */
data class ParsedFile(
    val path: String,
    val offenses: List<ParsedOffense>,
)

/**
 * Summary counts from docscribe JSON output.
 */
data class ParsedSummary(
    @SerializedName("offense_count") val offenseCount: Int,
    @SerializedName("target_file_count") val targetFileCount: Int,
    @SerializedName("inspected_file_count") val inspectedFileCount: Int,
    @SerializedName("error_count") val errorCount: Int,
)

/**
 * Top-level structure of docscribe JSON output.
 */
data class DocscribeOutput(
    val metadata: Map<String, String>?,
    val files: List<ParsedFile>,
    val summary: ParsedSummary?,
)

/**
 * Parsed summary from docscribe text output.
 */
data class TextSummary(
    val status: String,
    val inspectedCount: Int = 0,
    val needsUpdateCount: Int = 0,
    val typeMismatchCount: Int = 0,
    val errorCount: Int = 0,
    val okCount: Int = 0,
    val updatedCount: Int = 0,
)

/**
 * Parses docscribe CLI output (both JSON and text formats).
 */
object DocscribeOutputParser {
    private val gson = Gson()

    private val okRegex =
        Regex(
            """Docscribe: OK \((\d+) files checked(?:, (\d+) with type mismatches)?""",
        )
    private val failedRegex =
        Regex(
            """Docscribe: FAILED \((\d+) need updates, (\d+) type mismatches, (\d+) errors, (\d+) ok\)""",
        )
    private val updatedRegex =
        Regex(
            """Docscribe: updated (\d+) file\(s\)""",
        )
    private val wouldUpdateRegex =
        Regex(
            """Would update: (.+)""",
        )
    private val changeDetailRegex =
        Regex(
            """\s*-\s+(.+)""",
        )
    private val typeMismatchRegex =
        Regex(
            """Type mismatches: (.+)""",
        )
    private val errorProcessingRegex =
        Regex(
            """Error processing: (.+)""",
        )

    /**
     * Parse docscribe JSON output into a structured [DocscribeOutput].
     *
     * @param jsonString Raw JSON output from docscribe `--format json`.
     * @return Parsed output or `null` on malformed JSON.
     */
    fun parseJson(jsonString: String): DocscribeOutput? =
        try {
            val type = object : TypeToken<DocscribeOutput>() {}.type
            gson.fromJson<DocscribeOutput>(jsonString, type)
        } catch (_: Exception) {
            null
        }

    /**
     * Result of parsing docscribe text-mode output.
     */
    data class TextParseResult(
        val summary: TextSummary,
        val wouldUpdateFiles: List<Pair<String, List<String>>> = emptyList(),
        val typeMismatchFiles: List<String> = emptyList(),
        val errorFiles: List<String> = emptyList(),
    )

    /**
     * Parse docscribe text output (non-JSON mode).
     *
     * Extracts the summary line ("Docscribe: OK / FAILED / updated") and lists of
     * would-update files, type-mismatch files, and error files.
     *
     * @param text Raw text output from docscribe.
     * @return Parsed result or `null` if no summary line is found.
     */
    fun parseTextOutput(text: String): TextParseResult? {
        val lines = text.lines()
        if (lines.isEmpty()) return null

        val summaryLine =
            lines.find { it.startsWith("Docscribe:") }
                ?: return null

        val summary = parseSummaryLine(summaryLine) ?: return null

        val wouldUpdateFiles = mutableListOf<Pair<String, List<String>>>()
        val typeMismatchFiles = mutableListOf<String>()
        val errorFiles = mutableListOf<String>()

        processOutputLines(lines, wouldUpdateFiles, typeMismatchFiles, errorFiles)

        return TextParseResult(summary, wouldUpdateFiles, typeMismatchFiles, errorFiles)
    }

    /**
     * Process lines after the summary, collecting would-update, type-mismatch and error entries.
     *
     * Mutates the provided lists directly. Tracks current "Would update:" file path and its
     * change details across consecutive lines.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun processOutputLines(
        lines: List<String>,
        wouldUpdateFiles: MutableList<Pair<String, List<String>>>,
        typeMismatchFiles: MutableList<String>,
        errorFiles: MutableList<String>,
    ) {
        var currentWouldUpdate: String? = null
        var currentDetails = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("Would update:") -> {
                    val prev = finalizeWouldUpdate(currentWouldUpdate, currentDetails)
                    if (prev != null) wouldUpdateFiles.add(prev)
                    currentWouldUpdate = wouldUpdateRegex.find(line)?.groupValues?.getOrNull(1)
                    currentDetails = mutableListOf()
                }

                changeDetailRegex.matches(line) && currentWouldUpdate != null -> {
                    val detail = changeDetailRegex.find(line)?.groupValues?.getOrNull(1)
                    if (detail != null) currentDetails.add(detail)
                }

                line.startsWith("Type mismatches:") -> {
                    val file = typeMismatchRegex.find(line)?.groupValues?.getOrNull(1)
                    if (file != null) typeMismatchFiles.add(file)
                }

                line.startsWith("Error processing:") -> {
                    val file = errorProcessingRegex.find(line)?.groupValues?.getOrNull(1)
                    if (file != null) errorFiles.add(file)
                }
            }
        }
        if (currentWouldUpdate != null) {
            wouldUpdateFiles.add(currentWouldUpdate to currentDetails.toList())
        }
    }

    /**
     * Finalize a "Would update:" entry by pairing the file path with its details.
     *
     * @param filePath  The current file being tracked, or `null`.
     * @param details   The details accumulated for this file.
     * @return A pair of (filePath, details) if [filePath] is not null, or `null`.
     */
    private fun finalizeWouldUpdate(
        filePath: String?,
        details: MutableList<String>,
    ): Pair<String, List<String>>? = if (filePath != null) filePath to details.toList() else null

    /**
     * Parse a single "Docscribe:" summary line into a [TextSummary].
     *
     * Handles three formats:
     * - OK: `Docscribe: OK (N files checked)`
     * - FAILED: `Docscribe: FAILED (N need updates, M type mismatches, ...)`
     * - UPDATED: `Docscribe: updated N file(s)`
     */
    private fun parseSummaryLine(line: String): TextSummary? {
        okRegex.find(line)?.let { m -> return parseOkSummary(m) }
        failedRegex.find(line)?.let { m -> return parseFailedSummary(m) }
        updatedRegex.find(line)?.let { m -> return parseUpdatedSummary(m) }
        return null
    }

    /**
     * Parse an "OK" summary line into a [TextSummary].
     *
     * Format: `Docscribe: OK (N files checked, M type mismatches)`.
     */
    private fun parseOkSummary(m: MatchResult): TextSummary =
        TextSummary(
            status = "OK",
            inspectedCount = m.groupValues[1].toIntOrNull() ?: 0,
            typeMismatchCount = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0,
        )

    /**
     * Parse a "FAILED" summary line into a [TextSummary].
     *
     * Format: `Docscribe: FAILED (N need updates, M type mismatches, E errors, O ok)`.
     */
    private fun parseFailedSummary(m: MatchResult): TextSummary {
        val needsUpdate = m.groupValues[1].toIntOrNull() ?: 0
        val typeMismatches = m.groupValues[2].toIntOrNull() ?: 0
        val errors = m.groupValues[3].toIntOrNull() ?: 0
        val ok = m.groupValues[4].toIntOrNull() ?: 0
        return TextSummary(
            status = "FAILED",
            needsUpdateCount = needsUpdate,
            typeMismatchCount = typeMismatches,
            errorCount = errors,
            okCount = ok,
            inspectedCount = needsUpdate + typeMismatches + errors + ok,
        )
    }

    /**
     * Parse an "UPDATED" summary line into a [TextSummary].
     *
     * Format: `Docscribe: updated N file(s)`.
     */
    private fun parseUpdatedSummary(m: MatchResult): TextSummary =
        TextSummary(
            status = "UPDATED",
            updatedCount = m.groupValues[1].toIntOrNull() ?: 0,
        )
}
