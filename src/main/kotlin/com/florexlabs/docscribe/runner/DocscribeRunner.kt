package com.florexlabs.docscribe.runner

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File

/**
 * The strategy determines which docscribe CLI flags are used.
 *
 * - [CHECK] — dry-run, reports missing documentation.
 * - [SAFE] — add missing `@param` / `@return` tags (`-a -B`).
 * - [AGGRESSIVE] — generate full YARD documentation (`-A -k -B`).
 */
enum class DocscribeStrategy {
    CHECK,
    SAFE,
    AGGRESSIVE,
}

/**
 * Input parameters for a single docscribe invocation.
 *
 * @property projectDir  Absolute path to the project root (must contain a `Gemfile`).
 * @property file        Path to a specific Ruby file to target, or `null` for workspace-wide.
 * @property strategy    Which fix strategy to apply; defaults to [DocscribeStrategy.CHECK].
 * @property formatJson  Whether to pass `--format json` (only meaningful for [DocscribeStrategy.CHECK]).
 * @property subcommand  Optional subcommand like `"update_types"` — takes priority over [strategy].
 */
data class RunOptions(
    val projectDir: String,
    val file: String? = null,
    val strategy: DocscribeStrategy = DocscribeStrategy.CHECK,
    val formatJson: Boolean = true,
    val subcommand: String? = null,
)

/**
 * The structured result of a docscribe execution.
 *
 * @property success   `true` when the process completed without errors (exit code != 2).
 * @property hasIssues `true` when docscribe found documentation issues (exit code == 1).
 * @property exitCode  Raw process exit code.
 * @property stdout    Standard output from the process.
 * @property stderr    Standard error from the process.
 */
data class RunResult(
    val success: Boolean,
    val hasIssues: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /**
     * Combined output: prefers `stdout`, falls back to `stderr` if `stdout` is blank.
     */
    @Suppress("unused")
    val output: String get() = if (stderr.isBlank()) stdout else "$stdout\n$stderr"
}

/**
 * Pluggable strategy for running external processes.
 *
 * Used in tests to avoid actually spawning a process.
 */
interface CommandExecutor {
    /**
     * Execute a command and return the result.
     *
     * @param cmd  The executable to run (e.g. `"bundle"`).
     * @param args Arguments to pass to the executable.
     * @param cwd  Working directory for the process.
     */
    fun execute(
        cmd: String,
        args: List<String>,
        cwd: String,
    ): RunResult
}

/**
 * Default [CommandExecutor] that uses IntelliJ's [GeneralCommandLine] and [CapturingProcessHandler].
 *
 * Applies a 120-second timeout. Exit code 2 is treated as a failure (indistinguishable from timeout).
 */
class DefaultCommandExecutor : CommandExecutor {
    override fun execute(
        cmd: String,
        args: List<String>,
        cwd: String,
    ): RunResult {
        val commandLine =
            GeneralCommandLine(cmd)
                .withParameters(args)
                .withWorkDirectory(cwd)
        val handler = CapturingProcessHandler(commandLine)
        val output =
            try {
                handler.runProcess(120000)
            } catch (_: ExecutionException) {
                return RunResult(
                    success = false,
                    hasIssues = false,
                    exitCode = 2,
                    stdout = "",
                    stderr = "Process timed out or failed after 120s",
                )
            }
        val exitCode = output.exitCode
        return RunResult(
            success = exitCode != 2,
            hasIssues = exitCode == 1,
            exitCode = exitCode,
            stdout = output.stdout,
            stderr = output.stderr,
        )
    }
}

/**
 * Core runner for the docscribe CLI.
 *
 * Provides:
 * - [findProjectRoot] — locate the project root by walking up for a `Gemfile`.
 * - [getCommandArgs] — build CLI arguments from a [DocscribeStrategy].
 * - [runDocscribe] — execute `bundle exec docscribe` with the given options.
 */
object DocscribeRunner {
    private const val MAX_DEPTH = 20

    /**
     * Walk up from [startPath] looking for a `Gemfile`.
     *
     * Searches up to [MAX_DEPTH] levels. Returns the directory containing the `Gemfile`,
     * or `null` if none is found.
     *
     * @param startPath The path to start searching from (typically a file within the project).
     * @return Absolute path to the project root, or `null`.
     */
    fun findProjectRoot(startPath: String): String? {
        var current = File(startPath).canonicalFile
        repeat(MAX_DEPTH) {
            if (File(current, "Gemfile").exists()) return current.absolutePath
            val parent = current.parentFile ?: return null
            current = parent
        }
        return null
    }

    /**
     * Build the CLI argument list for `bundle exec docscribe <args>`.
     *
     * Strategy-to-args mapping:
     * - [DocscribeStrategy.CHECK] — no flags (unless [formatJson] adds `--format json`).
     * - [DocscribeStrategy.SAFE] — `-a -B`.
     * - [DocscribeStrategy.AGGRESSIVE] — `-A -k -B`.
     *
     * @param strategy   The fix strategy.
     * @param formatJson Whether to include `--format json` (check-only).
     * @param filePath   Optional specific file path to pass as the last argument.
     * @return A list of CLI arguments.
     */
    fun getCommandArgs(
        strategy: DocscribeStrategy,
        formatJson: Boolean,
        filePath: String? = null,
    ): List<String> {
        val args = mutableListOf<String>()
        when (strategy) {
            DocscribeStrategy.SAFE -> {
                args.add("-a")
                args.add("-B")
            }

            DocscribeStrategy.AGGRESSIVE -> {
                args.addAll(listOf("-A", "-k", "-B"))
            }

            DocscribeStrategy.CHECK -> {}
        }
        if (formatJson && strategy == DocscribeStrategy.CHECK) {
            args.addAll(listOf("--format", "json"))
        }
        if (filePath != null) {
            args.add(filePath)
        }
        return args
    }

    /**
     * Execute `bundle exec docscribe` with the given [options].
     *
     * If [RunOptions.subcommand] is set it takes priority over [RunOptions.strategy],
     * producing `bundle exec docscribe <subcommand> <projectDir>` instead of the normal
     * strategy-based argument list.
     *
     * @param options  The run parameters.
     * @param executor The process runner; defaults to [DefaultCommandExecutor].
     * @return The [RunResult] from the execution.
     */
    fun runDocscribe(
        options: RunOptions,
        executor: CommandExecutor = DefaultCommandExecutor(),
    ): RunResult {
        val projectRoot = options.projectDir
        val args =
            if (options.subcommand != null) {
                listOf(options.subcommand, projectRoot)
            } else {
                getCommandArgs(options.strategy, options.formatJson, options.file)
            }
        return executor.execute("bundle", listOf("exec", "docscribe") + args, projectRoot)
    }
}
