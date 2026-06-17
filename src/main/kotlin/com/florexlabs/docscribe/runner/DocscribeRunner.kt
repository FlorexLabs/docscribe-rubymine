package com.florexlabs.docscribe.runner

import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File

/**
 * The docscribe strategy type: check for diagnostics, or apply safe/aggressive fixes.
 */
enum class DocscribeStrategy {
    CHECK,
    SAFE,
    AGGRESSIVE,
}

/**
 * Options passed to [DocscribeRunner.runDocscribe].
 *
 * @property projectDir  Absolute path to the project root (containing Gemfile).
 * @property file        Specific Ruby file to check/fix, or `null` for all files.
 * @property strategy    Which docscribe strategy to use.
 * @property formatJson  Whether to request JSON output (only used for CHECK).
 * @property subcommand  Optional subcommand (e.g. `"update_types"`) — when set, [strategy] is ignored.
 */
data class RunOptions(
    val projectDir: String,
    val file: String? = null,
    val strategy: DocscribeStrategy = DocscribeStrategy.CHECK,
    val formatJson: Boolean = true,
    val subcommand: String? = null,
)

/**
 * Result of a docscribe command execution.
 *
 * @property success   `true` when exit code is *not* 2 (fatal error).
 * @property hasIssues `true` when exit code is 1 (findings reported).
 * @property exitCode  Raw process exit code (0 = OK, 1 = findings, 2 = error).
 * @property stdout    Standard output text.
 * @property stderr    Standard error text.
 */
data class RunResult(
    val success: Boolean,
    val hasIssues: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    @Suppress("unused")
    val output: String get() = if (stderr.isBlank()) stdout else "$stdout\n$stderr"
}

/**
 * Abstraction over process execution for testability.
 */
interface CommandExecutor {
    fun execute(
        cmd: String,
        args: List<String>,
        cwd: String,
    ): RunResult
}

/**
 * Default [CommandExecutor] that spawns a real OS process via [GeneralCommandLine].
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
        val output = handler.runProcess()
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
 * Entry-point for running the docscribe CLI.
 *
 * All public methods are stateless; [findProjectRoot] traverses upward to locate the Gemfile,
 * [gemfileHasRbs] checks for the `rbs` gem dependency, and [getCommandArgs] builds the argument
 * list for the given strategy and options.
 */
object DocscribeRunner {
    private const val MAX_DEPTH = 20

    /**
     * Walk up from [startPath] looking for a directory that contains a `Gemfile`.
     *
     * @param startPath  Absolute path to start searching from.
     * @return Absolute path of the directory containing `Gemfile`, or `null` if none found.
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
     * Check whether a Gemfile contains a `gem "rbs"` declaration.
     *
     * @param gemfilePath  Absolute path to the Gemfile.
     * @return `true` if the `rbs` gem is listed.
     */
    fun gemfileHasRbs(gemfilePath: String): Boolean =
        try {
            val content = File(gemfilePath).readText()
            Regex("""gem\s+['"]rbs['"]""").containsMatchIn(content)
        } catch (_: Exception) {
            false
        }

    /**
     * Build the CLI argument list for the docscribe command.
     *
     * @param strategy        Target strategy (CHECK, SAFE, AGGRESSIVE).
     * @param formatJson      Request JSON output (only effective for CHECK).
     * @param useRbs          Add `--rbs-collection` flag.
     * @param filePath        Optional specific file path to scope the operation.
     * @param omitBoilerplate Add `-B` flag for safe/aggressive modes.
     */
    fun getCommandArgs(
        strategy: DocscribeStrategy,
        formatJson: Boolean,
        useRbs: Boolean,
        filePath: String? = null,
        omitBoilerplate: Boolean = false,
    ): List<String> {
        val args = mutableListOf<String>()
        when (strategy) {
            DocscribeStrategy.SAFE -> {
                args.add("-a")
                if (omitBoilerplate) args.add("-B")
            }

            DocscribeStrategy.AGGRESSIVE -> {
                args.addAll(listOf("-A", "-k"))
                if (omitBoilerplate) args.add("-B")
            }

            DocscribeStrategy.CHECK -> {}
        }
        if (formatJson && strategy == DocscribeStrategy.CHECK) {
            args.addAll(listOf("--format", "json"))
        }
        if (useRbs) {
            args.add("--rbs-collection")
        }
        if (filePath != null) {
            args.add(filePath)
        }
        return args
    }

    /**
     * Execute docscribe with the given options.
     *
     * @param options   Run configuration (strategy, file, etc.).
     * @param settings  Settings object (retrieved via singleton by default).
     * @param executor  Process executor (defaults to real OS process).
     */
    @Suppress("unused")
    fun runDocscribe(
        options: RunOptions,
        settings: DocscribeSettings = DocscribeSettings.getInstance(),
        executor: CommandExecutor = DefaultCommandExecutor(),
    ): RunResult {
        val projectRoot = options.projectDir
        val strategy = options.strategy
        val formatJson = options.formatJson
        val rbsEnabled = settings.useRbs
        val useRbs = rbsEnabled && gemfileHasRbs("$projectRoot/Gemfile")
        val args =
            if (options.subcommand != null) {
                listOf(options.subcommand, projectRoot)
            } else {
                getCommandArgs(strategy, formatJson, useRbs, options.file, settings.omitBoilerplate)
            }
        val useBundleExec = settings.useBundleExec
        val commandPath = settings.commandPath
        return if (useBundleExec) {
            executor.execute("bundle", listOf("exec", commandPath) + args, projectRoot)
        } else {
            executor.execute(commandPath, args, projectRoot)
        }
    }
}
