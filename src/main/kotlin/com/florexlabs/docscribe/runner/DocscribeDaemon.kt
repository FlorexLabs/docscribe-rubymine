package com.florexlabs.docscribe.runner

import com.florexlabs.docscribe.settings.DocscribeSettings
import com.google.gson.GsonBuilder
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

@Service(Service.Level.PROJECT)
class DocscribeDaemon(
    private val project: Project,
) : Disposable {
    private val log = Logger.getInstance(DocscribeDaemon::class.java)
    private val gson = GsonBuilder().create()
    private val lock = Any()

    @Volatile
    private var server: ServerHandle? = null

    @Volatile
    private var alive = false

    private data class ServerHandle(
        val socketPath: Path,
        val process: Process,
    )

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
            val handle = ensureRunning(projectDir) ?: return fallback(command, file, projectDir, formatJson)
            val params =
                mutableMapOf<String, Any?>(
                    "file" to file,
                    "project_dir" to (projectDir ?: project.basePath ?: ""),
                )
            if (useRbs) params["rbs"] = true
            if (noBoilerplate) params["no_boilerplate"] = true

            val response =
                when (command) {
                    "check" -> rpcCall(handle, "check", params)

                    "safe_fix" -> rpcCall(handle, "fix", params + mapOf("strategy" to "safe"))

                    "aggressive_fix" -> rpcCall(handle, "fix", params + mapOf("strategy" to "aggressive"))

                    "ping" -> rpcCall(handle, "ping")

                    "update_types" -> rpcCall(handle, "update_types")

                    else -> return RunResult(
                        success = false,
                        hasIssues = false,
                        exitCode = 1,
                        stdout = "",
                        stderr = "Unknown command: $command",
                    )
                }

            if (response == null) {
                log.warn("RPC returned null for $command, falling back")
                return fallback(command, file, projectDir, formatJson)
            }

            val error = response["error"]
            if (error != null) {
                val msg = (error as? Map<*, *>)?.get("message")?.toString() ?: error.toString()
                return RunResult(success = false, hasIssues = false, exitCode = 1, stdout = "", stderr = "Server error: $msg")
            }

            val result = response["result"]
            if (command == "check") {
                val changes = (result as? Map<*, *>)?.get("changes") as? List<*> ?: emptyList<Any>()
                val jsonOutput = buildCheckJson(file ?: "", changes)
                val parsed = gson.fromJson(jsonOutput, Map::class.java)

                @Suppress("UNCHECKED_CAST")
                val files = (parsed as? Map<String, Any?>)?.get("files") as? List<Map<String, Any?>>
                val totalOffenses =
                    files?.sumOf { f ->
                        (f["offenses"] as? List<*>)?.size ?: 0
                    } ?: 0
                return RunResult(
                    success = true,
                    hasIssues = totalOffenses > 0,
                    exitCode = if (totalOffenses > 0) 1 else 0,
                    stdout = jsonOutput,
                    stderr = "",
                )
            }

            val fixOutput = gson.toJson(result)
            val trimmed = if (fixOutput.length > OUTPUT_TRIM_LENGTH) fixOutput.take(OUTPUT_TRIM_LENGTH) + "..." else fixOutput
            return RunResult(success = true, hasIssues = false, exitCode = 0, stdout = trimmed, stderr = "")
        }
    }

    private fun strategyFromCommand(command: String): DocscribeStrategy =
        when (command) {
            "safe_fix" -> DocscribeStrategy.SAFE
            "aggressive_fix" -> DocscribeStrategy.AGGRESSIVE
            else -> DocscribeStrategy.CHECK
        }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureRunning(projectDir: String?): ServerHandle? {
        val existing = server
        if (existing != null && alive) return existing

        val ruby = rubyCommand() ?: return null
        val gemRoot =
            DocscribeRunner.findProjectRoot(projectDir ?: project.basePath ?: "")
                ?: return null

        val script =
            "require 'docscribe/server'; " +
                "Docscribe::Server.ensure_running!(daemonize: false, timeout: $STARTUP_TIMEOUT_SECONDS); " +
                "puts Docscribe::Server.socket_path"

        val pb =
            ProcessBuilder(ruby, "-e", script)
                .directory(File(gemRoot))
        val env = pb.environment()
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        if (sdk?.homePath != null) {
            val sdkBin = File(sdk.homePath, "bin").absolutePath
            val currentPath = env["PATH"] ?: ""
            env["PATH"] = "$sdkBin${File.pathSeparator}$currentPath"
            env["BUNDLE_GEMFILE"] = File(gemRoot, "Gemfile").absolutePath
        }

        val localGemPath = System.getProperty("docscribe.local.gem.path")
        if (localGemPath != null) {
            val libPath = "$localGemPath/lib"
            val existingRubyLib = env["RUBYLIB"]
            env["RUBYLIB"] = if (existingRubyLib != null) "$existingRubyLib:$libPath" else libPath
        }

        pb.redirectErrorStream(true)
        val proc =
            try {
                pb.start()
            } catch (e: Exception) {
                log.warn("Failed to start docscribe server", e)
                return null
            }

        val reader = proc.inputStream.bufferedReader()
        val socketPathLine =
            try {
                reader.readLine()
            } catch (_: Exception) {
                null
            }

        val exitCode = proc.waitFor(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!exitCode) {
            proc.destroyForcibly()
            log.warn("Server startup timed out after ${STARTUP_TIMEOUT_SECONDS}s")
            showNotification("DocScribe server startup timed out")
            return null
        }

        if (socketPathLine.isNullOrBlank() || proc.exitValue() != 0) {
            val err = reader.readText()
            log.warn("Server failed to start: $socketPathLine $err")
            showNotification("DocScribe server failed to start: ${socketPathLine ?: err}")
            return null
        }

        val socketPath = Path.of(socketPathLine.trim())
        val handle = ServerHandle(socketPath, proc)
        server = handle
        alive = true
        log.info("Docscribe server started on socket $socketPath")
        return handle
    }

    private fun showNotification(message: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(message, NotificationType.ERROR).notify(project)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rubyCommand(): String? {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        if (sdk?.homePath != null) {
            val rubyPath = "${sdk.homePath}/bin/ruby"
            if (File(rubyPath).canExecute()) return rubyPath
            log.warn("Ruby SDK configured but binary not found at $rubyPath, falling back to shell")
        } else {
            log.warn("No Ruby SDK configured, falling back to shell discovery")
        }

        return try {
            val proc = ProcessBuilder("bash", "-lc", "which ruby").start()
            val path =
                proc.inputStream
                    .bufferedReader()
                    .readLine()
                    ?.trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            if (path != null && path.isNotBlank() && File(path).canExecute()) path else null
        } catch (e: Exception) {
            log.warn("Failed to find Ruby via shell", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rpcCall(
        handle: ServerHandle,
        method: String,
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?>? {
        val request =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to method,
                "params" to params,
            )
        val requestJson = gson.toJson(request)

        try {
            val address = UnixDomainSocketAddress.of(handle.socketPath)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(address)
                val buf = ByteBuffer.wrap(requestJson.toByteArray())
                channel.write(buf)

                val responseBuf = ByteBuffer.allocate(RPC_BUFFER_SIZE)
                channel.read(responseBuf)
                responseBuf.flip()
                val responseBytes = ByteArray(responseBuf.remaining())
                responseBuf.get(responseBytes)
                val responseStr = String(responseBytes)

                @Suppress("UNCHECKED_CAST")
                return gson.fromJson(responseStr, Map::class.java) as? Map<String, Any?>
            }
        } catch (e: Exception) {
            log.warn("RPC call '$method' failed", e)
            return null
        }
    }

    private fun fallback(
        command: String,
        file: String?,
        projectDir: String?,
        formatJson: Boolean,
    ): RunResult {
        val settings = DocscribeSettings.getInstance()
        val strategy = strategyFromCommand(command)
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
        alive = false
        server = null
    }

    override fun dispose() {
        synchronized(lock) {
            val srv = server ?: return
            try {
                rpcCall(srv, "shutdown")
            } catch (_: Exception) {
            }
            die()
        }
    }

    companion object {
        private const val STARTUP_TIMEOUT_SECONDS = 15L
        private const val OUTPUT_TRIM_LENGTH = 500
        private const val RPC_BUFFER_SIZE = 65536

        fun buildCheckJson(
            filePath: String,
            changes: List<*>,
        ): String {
            val gsonLocal = GsonBuilder().create()
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
            return gsonLocal.toJson(output)
        }

        @JvmStatic
        fun getInstance(project: Project): DocscribeDaemon = project.getService(DocscribeDaemon::class.java)

        @Suppress("TooGenericExceptionCaught")
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
                projectDir = options.projectDir.let { d -> DocscribeRunner.findProjectRoot(d) ?: d },
                formatJson = options.formatJson,
                useRbs = settings.useRbs,
                noBoilerplate = settings.omitBoilerplate,
            )
        }
    }
}
