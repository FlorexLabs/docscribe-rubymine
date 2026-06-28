package com.florexlabs.docscribe.runner

import com.florexlabs.docscribe.settings.DocscribeSettings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * JSON response from the docscribe daemon process.
 */
private data class DaemonResponse(
    val id: Int,
    @SerializedName("exit_code") val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Project-level service managing a long-lived docscribe Ruby process.
 *
 * The daemon loads docscribe + Bundler once, reusing them for all subsequent
 * requests. This avoids the 2-3s Ruby VM startup cost per invocation.
 * Falls back to [DefaultCommandExecutor] if the daemon cannot be started.
 */
@Service(Service.Level.PROJECT)
class DocscribeDaemon(
    private val project: Project,
) : Disposable {
    private val log = Logger.getInstance(DocscribeDaemon::class.java)
    private val gson: Gson = GsonBuilder().create()
    private val nextId = AtomicInteger(1)
    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: java.io.BufferedWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var alive = false

    /**
     * Execute a docscribe command via the daemon process.
     * Falls back to CLI if the daemon is not running.
     */
    @Suppress("TooGenericExceptionCaught")
    fun execute(
        command: String,
        file: String? = null,
        projectDir: String? = null,
        formatJson: Boolean = false,
        useRbs: Boolean = false,
        noBoilerplate: Boolean = false,
    ): RunResult {
        synchronized(lock) {
            ensureRunning(projectDir)
            if (!alive) {
                return fallback(command, file, projectDir, formatJson)
            }
            val id = nextId.getAndIncrement()
            val request = buildRequest(id, command, file, formatJson, useRbs, noBoilerplate)
            return try {
                sendRequest(request)
            } catch (e: Exception) {
                log.warn("Daemon request failed, falling back to CLI", e)
                die()
                fallback(command, file, projectDir, formatJson)
            }
        }
    }

    private fun buildRequest(
        id: Int,
        command: String,
        file: String?,
        formatJson: Boolean,
        useRbs: Boolean,
        noBoilerplate: Boolean,
    ): String {
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("command", command)
        if (file != null) obj.addProperty("file", file)
        obj.addProperty("format_json", formatJson)
        obj.addProperty("rbs", useRbs)
        obj.addProperty("no_boilerplate", noBoilerplate)
        return gson.toJson(obj)
    }

    private fun sendRequest(reqJson: String): RunResult {
        writer!!.write(reqJson)
        writer!!.newLine()
        writer!!.flush()

        val future = CompletableFuture.supplyAsync { reader!!.readLine() }
        val responseLine =
            try {
                future.get(30, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                throw ExecutionException("Daemon did not respond within 30s")
            }

        if (responseLine == null) {
            throw ExecutionException("Daemon process ended unexpectedly")
        }

        val response = gson.fromJson(responseLine, DaemonResponse::class.java)
        return RunResult(
            success = response.exitCode < 2,
            hasIssues = response.exitCode == 1,
            exitCode = response.exitCode,
            stdout = response.stdout,
            stderr = response.stderr,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureRunning(projectDir: String?) {
        if (alive) return
        val gemRoot =
            projectDir?.let { DocscribeRunner.findProjectRoot(it) }
                ?: project.basePath?.let { DocscribeRunner.findProjectRoot(it) }
                ?: return
        try {
            val wrapperPath = extractWrapper()
            val cmd = buildCommand(wrapperPath)

            val pb =
                ProcessBuilder(cmd)
                    .directory(File(gemRoot))
                    .redirectErrorStream(false)
            if (log.isDebugEnabled) {
                pb.environment()["DOCSCRIBE_DAEMON_DEBUG"] = "1"
            }
            // Set PATH to include SDK's bin dir so bundle/ruby are found together
            val sdk = ProjectRootManager.getInstance(project).projectSdk
            if (sdk?.homePath != null) {
                val rubyBin = File(sdk.homePath, "bin").absolutePath
                val currentPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "$rubyBin${File.pathSeparator}$currentPath"
                pb.environment()["BUNDLE_GEMFILE"] = File(gemRoot, "Gemfile").absolutePath
            }
            process = pb.start()
            failFastIfDead(process!!)
            writer = java.io.BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // Drain stderr in a background thread to prevent buffer deadlock
            val errReader = BufferedReader(InputStreamReader(process!!.errorStream))
            thread(isDaemon = true) {
                try {
                    var line = errReader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) log.warn("Daemon stderr: $line")
                        line = errReader.readLine()
                    }
                } catch (_: Exception) {
                }
            }

            val pong = sendPing()
            if (pong.exitCode != 0) {
                die()
                return
            }
            alive = true
            log.info("Docscribe daemon started (pid=${process!!.pid()}, gemRoot=$gemRoot)")
        } catch (e: Exception) {
            log.warn("Failed to start docscribe daemon", e)
            val cause = e.message ?: "Unknown error"
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("DocScribe")
                .createNotification("DocScribe daemon failed to start: $cause", NotificationType.WARNING)
                .notify(project)
            die()
        }
    }

    private fun buildCommand(wrapperPath: String): List<String> {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val sdkHome = sdk?.homePath
        if (sdkHome != null) {
            val rubyExe = File(sdkHome, "bin/ruby").absolutePath
            log.info("Using Ruby SDK: $rubyExe")
            return listOf(rubyExe, "-S", "bundle", "exec", "ruby", wrapperPath)
        }
        // Fallback: run through user's login shell for PATH discovery
        val shell = System.getenv("SHELL") ?: "/bin/bash"
        log.warn("No Ruby SDK found, falling back to shell: $shell -lc \"ruby ...\"")
        return listOf(shell, "-lc", "ruby '$wrapperPath'")
    }

    private fun failFastIfDead(proc: Process) {
        if (!proc.isAlive) {
            val stderr = proc.errorStream.bufferedReader().readText()
            throw ExecutionException("Daemon exited immediately: $stderr")
        }
        Thread.sleep(STARTUP_WAIT_MS)
        if (!proc.isAlive) {
            val stderr = proc.errorStream.bufferedReader().readText()
            throw ExecutionException("Daemon crashed after ${STARTUP_WAIT_MS}ms: $stderr")
        }
    }

    private fun sendPing(): DaemonResponse {
        val id = nextId.getAndIncrement()
        val req = gson.toJson(mapOf("id" to id, "command" to "ping"))
        writer!!.write(req)
        writer!!.newLine()
        writer!!.flush()
        val future = CompletableFuture.supplyAsync { reader!!.readLine() }
        val line =
            try {
                future.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        if (line == null) throw ExecutionException("Daemon did not respond to ping")
        return gson.fromJson(line, DaemonResponse::class.java)
    }

    private fun fallback(
        command: String,
        file: String?,
        projectDir: String?,
        formatJson: Boolean,
    ): RunResult {
        val settings = DocscribeSettings.getInstance()
        val strategy =
            when (command) {
                "safe_fix" -> DocscribeStrategy.SAFE
                "aggressive_fix" -> DocscribeStrategy.AGGRESSIVE
                else -> DocscribeStrategy.CHECK
            }
        val options =
            RunOptions(
                projectDir = projectDir ?: project.basePath ?: "",
                file = file,
                strategy = strategy,
                formatJson = formatJson,
            )
        return DocscribeRunner.runDocscribe(options, settings, DefaultCommandExecutor())
    }

    private fun die() {
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        alive = false
        process = null
        writer = null
        reader = null
    }

    override fun dispose() {
        synchronized(lock) {
            if (!alive) return
            try {
                val req = gson.toJson(mapOf("id" to 0, "command" to "shutdown"))
                writer?.write(req)
                writer?.newLine()
                writer?.flush()
                process?.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            die()
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val STARTUP_WAIT_MS = 200L
        private var wrapperFile: File? = null

        @JvmStatic
        fun getInstance(project: Project): DocscribeDaemon = project.getService(DocscribeDaemon::class.java)

        /**
         * Run docscribe via daemon if enabled in settings, otherwise fall back to CLI.
         * This is the main integration point for all actions and intentions.
         */
        fun executeWithFallback(
            project: Project,
            options: RunOptions,
            settings: DocscribeSettings = DocscribeSettings.getInstance(),
        ): RunResult {
            if (!settings.useDaemon) {
                return DocscribeRunner.runDocscribe(options, settings, DefaultCommandExecutor())
            }
            val daemon = getInstance(project)
            val command =
                when (options.subcommand) {
                    "update_types" -> {
                        "update_types"
                    }

                    else -> {
                        when (options.strategy) {
                            DocscribeStrategy.SAFE -> "safe_fix"
                            DocscribeStrategy.AGGRESSIVE -> "aggressive_fix"
                            DocscribeStrategy.CHECK -> "check"
                        }
                    }
                }
            return daemon.execute(
                command = command,
                file = options.file,
                projectDir =
                    options.projectDir.let { d ->
                        DocscribeRunner.findProjectRoot(d) ?: d
                    },
                formatJson = options.formatJson,
                useRbs = settings.useRbs,
                noBoilerplate = settings.omitBoilerplate,
            )
        }

        private fun extractWrapper(): String {
            wrapperFile?.let { if (it.exists()) return it.absolutePath }
            val tmp = File.createTempFile("docscribe-daemon-", ".rb")
            tmp.deleteOnExit()
            val stream =
                DocscribeDaemon::class.java.getResourceAsStream("/daemon/docscribe-daemon.rb")
                    ?: throw IllegalStateException("Wrapper script not found in JAR")
            tmp.outputStream().use { out -> stream.use { it.copyTo(out) } }
            tmp.setExecutable(true)
            wrapperFile = tmp
            return tmp.absolutePath
        }

        fun buildCheckJson(
            filePath: String,
            changes: List<*>,
        ): String {
            val gson = GsonBuilder().create()
            val offenses =
                changes.mapNotNull { change ->
                    if (change is Map<*, *>) {
                        val line = (change["line"] as? Number)?.toInt() ?: 1
                        mapOf(
                            "severity" to "convention",
                            "cop_name" to "DocScribe/MissingDocumentation",
                            "message" to "Missing YARD documentation",
                            "corrected" to false,
                            "correctable" to true,
                            "location" to
                                mapOf(
                                    "start_line" to line,
                                    "start_column" to 1,
                                    "last_line" to line,
                                    "last_column" to 1,
                                ),
                        )
                    } else {
                        null
                    }
                }
            val output =
                mapOf(
                    "metadata" to mapOf("docscribe_version" to "1.5.1"),
                    "files" to
                        listOf(
                            mapOf(
                                "path" to filePath,
                                "offenses" to offenses,
                            ),
                        ),
                    "summary" to
                        mapOf(
                            "offense_count" to offenses.size,
                            "target_file_count" to 1,
                            "inspected_file_count" to 1,
                            "error_count" to 0,
                        ),
                )
            return gson.toJson(output)
        }
    }
}
