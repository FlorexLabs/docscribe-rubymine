package com.florexlabs.docscribe.runner

import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File

enum class DocscribeStrategy {
    CHECK, SAFE, AGGRESSIVE
}

data class RunOptions(
    val projectDir: String,
    val file: String? = null,
    val strategy: DocscribeStrategy = DocscribeStrategy.CHECK,
    val formatJson: Boolean = true
)

data class RunResult(
    val success: Boolean,
    val hasIssues: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    @Suppress("unused")
    val output: String get() = if (stderr.isBlank()) stdout else "$stdout\n$stderr"
}

interface CommandExecutor {
    fun execute(cmd: String, args: List<String>, cwd: String): RunResult
}

class DefaultCommandExecutor : CommandExecutor {
    override fun execute(cmd: String, args: List<String>, cwd: String): RunResult {
        val commandLine = GeneralCommandLine(cmd)
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
            stderr = output.stderr
        )
    }
}

object DocscribeRunner {

    fun findProjectRoot(startPath: String): String? {
        var current = File(startPath).canonicalFile
        repeat(20) {
            if (File(current, "Gemfile").exists()) return current.absolutePath
            val parent = current.parentFile ?: return null
            current = parent
        }
        return null
    }

    fun gemfileHasRbs(gemfilePath: String): Boolean {
        return try {
            val content = File(gemfilePath).readText()
            Regex("""gem\s+['"]rbs['"]""").containsMatchIn(content)
        } catch (_: Exception) {
            false
        }
    }

    fun getCommandArgs(
        strategy: DocscribeStrategy,
        formatJson: Boolean,
        useRbs: Boolean,
        filePath: String? = null
    ): List<String> {
        val args = mutableListOf<String>()
        when (strategy) {
            DocscribeStrategy.SAFE -> args.add("-a")
            DocscribeStrategy.AGGRESSIVE -> args.add("-A")
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

    @Suppress("unused")
    fun runDocscribe(
        options: RunOptions,
        settings: DocscribeSettings = DocscribeSettings.getInstance(),
        executor: CommandExecutor = DefaultCommandExecutor()
    ): RunResult {
        val projectRoot = options.projectDir
        val strategy = options.strategy
        val formatJson = options.formatJson
        val rbsEnabled = settings.useRbs
        val useRbs = rbsEnabled && gemfileHasRbs("$projectRoot/Gemfile")
        val args = getCommandArgs(strategy, formatJson, useRbs, options.file)
        val useBundleExec = settings.useBundleExec
        val commandPath = settings.commandPath
        return if (useBundleExec) {
            executor.execute("bundle", listOf("exec", commandPath) + args, projectRoot)
        } else {
            executor.execute(commandPath, args, projectRoot)
        }
    }
}
