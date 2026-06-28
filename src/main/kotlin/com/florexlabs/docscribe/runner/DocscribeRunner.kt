package com.florexlabs.docscribe.runner

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File

enum class DocscribeStrategy {
    CHECK,
    SAFE,
    AGGRESSIVE,
}

data class RunOptions(
    val projectDir: String,
    val file: String? = null,
    val strategy: DocscribeStrategy = DocscribeStrategy.CHECK,
    val formatJson: Boolean = true,
    val subcommand: String? = null,
)

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

interface CommandExecutor {
    fun execute(
        cmd: String,
        args: List<String>,
        cwd: String,
    ): RunResult
}

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

object DocscribeRunner {
    private const val MAX_DEPTH = 20

    fun findProjectRoot(startPath: String): String? {
        var current = File(startPath).canonicalFile
        repeat(MAX_DEPTH) {
            if (File(current, "Gemfile").exists()) return current.absolutePath
            val parent = current.parentFile ?: return null
            current = parent
        }
        return null
    }

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
