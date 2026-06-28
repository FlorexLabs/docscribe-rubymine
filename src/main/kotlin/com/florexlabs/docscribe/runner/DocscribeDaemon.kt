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
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

@Suppress("TooManyFunctions")
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

    fun execute(
        command: String,
        file: String? = null,
        projectDir: String? = null,
        formatJson: Boolean = false,
        noBoilerplate: Boolean = false,
    ): RunResult {
        synchronized(lock) {
            val handle = ensureRunning(projectDir) ?: return fallback(command, file, projectDir, formatJson)
            val params = buildExecuteParams(file, projectDir, noBoilerplate)
            val response = performRpcCall(handle, command, params)
            return processRpcResponse(response, command, file, projectDir, formatJson)
        }
    }

    private fun buildExecuteParams(
        file: String?,
        projectDir: String?,
        noBoilerplate: Boolean,
    ): Map<String, Any?> {
        val params =
            mutableMapOf<String, Any?>(
                "file" to file,
                "project_dir" to (projectDir ?: project.basePath ?: ""),
            )
        if (noBoilerplate) params["no_boilerplate"] = true
        return params
    }

    private fun performRpcCall(
        handle: ServerHandle,
        command: String,
        params: Map<String, Any?>,
    ): Map<String, Any?>? =
        when (command) {
            "check" -> rpcCall(handle, "check", params)
            "safe_fix" -> rpcCall(handle, "fix", params + mapOf("strategy" to "safe"))
            "aggressive_fix" -> rpcCall(handle, "fix", params + mapOf("strategy" to "aggressive"))
            "ping" -> rpcCall(handle, "ping")
            "update_types" -> rpcCall(handle, "update_types")
            else -> null
        }

    private fun processRpcResponse(
        response: Map<String, Any?>?,
        command: String,
        file: String?,
        projectDir: String?,
        formatJson: Boolean,
    ): RunResult {
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
        if (command == "check") return processCheckResult(result, file ?: "")

        val fixOutput = gson.toJson(result)
        val trimmed = if (fixOutput.length > OUTPUT_TRIM_LENGTH) fixOutput.take(OUTPUT_TRIM_LENGTH) + "..." else fixOutput
        return RunResult(success = true, hasIssues = false, exitCode = 0, stdout = trimmed, stderr = "")
    }

    private fun processCheckResult(
        result: Any?,
        file: String,
    ): RunResult {
        val changes = (result as? Map<*, *>)?.get("changes") as? List<*> ?: emptyList<Any>()
        val jsonOutput = buildCheckJson(file, changes)
        val parsed = gson.fromJson(jsonOutput, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val files = (parsed as? Map<String, Any?>)?.get("files") as? List<Map<String, Any?>>
        val totalOffenses = files?.sumOf { f -> (f["offenses"] as? List<*>)?.size ?: 0 } ?: 0
        return RunResult(
            success = true,
            hasIssues = totalOffenses > 0,
            exitCode = if (totalOffenses > 0) 1 else 0,
            stdout = jsonOutput,
            stderr = "",
        )
    }

    private fun strategyFromCommand(command: String): DocscribeStrategy =
        when (command) {
            "safe_fix" -> DocscribeStrategy.SAFE
            "aggressive_fix" -> DocscribeStrategy.AGGRESSIVE
            else -> DocscribeStrategy.CHECK
        }

    private fun ensureRunning(projectDir: String?): ServerHandle? {
        val existing = server
        if (existing != null && alive) return existing

        val ruby = rubyCommand()
        val gemRoot = if (ruby != null) DocscribeRunner.findProjectRoot(projectDir ?: project.basePath ?: "") else null
        val proc = if (gemRoot != null) startServerProcess(ruby!!, gemRoot) else null
        val output = if (proc != null) readServerStartupOutput(proc) else null
        return if (output != null) resolveServerHandle(proc!!, output.first, output.second) else null
    }

    private fun resolveServerHandle(
        proc: Process,
        socketPathLine: String?,
        stderrText: String,
    ): ServerHandle? {
        if (socketPathLine.isNullOrBlank() || proc.exitValue() != 0) {
            log.warn("Server failed to start: $socketPathLine $stderrText")
            showNotification("DocScribe server failed to start: ${socketPathLine ?: stderrText}")
            return null
        }
        val socketPath = Path.of(socketPathLine.trim())
        val handle = ServerHandle(socketPath, proc)
        server = handle
        alive = true
        log.info("Docscribe server started on socket $socketPath")
        return handle
    }

    private fun startServerProcess(
        ruby: String,
        gemRoot: String,
    ): Process? {
        val script =
            "require 'bundler/setup'; " +
                "require 'docscribe/server'; " +
                "Docscribe::Server.ensure_running!(daemonize: false, timeout: $STARTUP_TIMEOUT_SECONDS); " +
                "puts Docscribe::Server.socket_path"

        val pb = ProcessBuilder(ruby, "-e", script).directory(File(gemRoot))
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

        pb.redirectErrorStream(false)
        return try {
            pb.start()
        } catch (e: IOException) {
            log.warn("Failed to start docscribe server", e)
            null
        }
    }

    private fun readServerStartupOutput(proc: Process): Pair<String?, String>? {
        val socketPathLine =
            try {
                proc.inputStream.bufferedReader().readLine()
            } catch (_: IOException) {
                null
            }

        val exited = proc.waitFor(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!exited) {
            proc.destroyForcibly()
            log.warn("Server startup timed out after ${STARTUP_TIMEOUT_SECONDS}s")
            showNotification("DocScribe server startup timed out")
            return null
        }

        val stderrText =
            try {
                proc.errorStream.bufferedReader().readText()
            } catch (_: IOException) {
                ""
            }
        return Pair(socketPathLine, stderrText)
    }

    private fun showNotification(message: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(message, NotificationType.ERROR).notify(project)
    }

    private fun rubyCommand(): String? {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        if (sdk?.homePath != null) {
            val rubyPath = "${sdk.homePath}/bin/ruby"
            if (File(rubyPath).canExecute()) return rubyPath
            log.warn("Ruby SDK configured but binary not found at $rubyPath, falling back to PATH")
        }

        val rubyFromPath = findRubyOnPath()
        if (rubyFromPath != null) return rubyFromPath

        log.warn("No Ruby found on PATH or via SDK")
        return null
    }

    private fun findRubyOnPath(): String? {
        val homeDir = System.getProperty("user.home")
        val rbenvShims = "$homeDir/.rbenv/shims/ruby"
        if (File(rbenvShims).canExecute()) return rbenvShims

        val proc =
            try {
                ProcessBuilder("which", "ruby").start()
            } catch (e: IOException) {
                log.warn("Failed to run 'which ruby'", e)
                return null
            }
        val path =
            try {
                proc.inputStream.bufferedReader().readLine()
            } catch (_: IOException) {
                null
            }?.trim()
        proc.waitFor(RUBY_PATH_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return if (path != null && path.isNotBlank() && File(path).canExecute()) path else null
    }

    private fun rpcCall(
        handle: ServerHandle,
        method: String,
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?>? {
        val requestJson = buildRpcRequestJson(method, params)

        try {
            val address = UnixDomainSocketAddress.of(handle.socketPath)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(address)
                val buf = ByteBuffer.wrap(requestJson.toByteArray(StandardCharsets.UTF_8))
                channel.write(buf)
                channel.shutdownOutput()

                val responseBytes = mutableListOf<Byte>()
                val readBuf = ByteBuffer.allocate(RPC_BUFFER_SIZE)
                while (channel.read(readBuf) > 0) {
                    readBuf.flip()
                    while (readBuf.hasRemaining()) {
                        responseBytes.add(readBuf.get())
                    }
                    readBuf.clear()
                }
                val responseStr = String(responseBytes.toByteArray(), StandardCharsets.UTF_8)

                return parseRpcResponse(responseStr)
            }
        } catch (e: IOException) {
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
        val strategy = strategyFromCommand(command)
        val options =
            RunOptions(
                projectDir = projectDir ?: project.basePath ?: "",
                file = file,
                strategy = strategy,
                formatJson = formatJson,
            )
        return DocscribeRunner.runDocscribe(options, DefaultCommandExecutor())
    }

    private fun die() {
        alive = false
        server = null
    }

    @Suppress("TooGenericExceptionCaught")
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
        private const val RUBY_PATH_LOOKUP_TIMEOUT_SECONDS = 3L
        private val sharedGson by lazy { GsonBuilder().create() }

        @JvmStatic
        fun buildRpcRequestJson(
            method: String,
            params: Map<String, Any?> = emptyMap(),
        ): String {
            val request =
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to method,
                    "params" to params,
                )
            return "${sharedGson.toJson(request)}\n"
        }

        @Suppress("TooGenericExceptionCaught")
        @JvmStatic
        fun parseRpcResponse(responseStr: String): Map<String, Any?>? {
            if (responseStr.isBlank()) return null
            @Suppress("UNCHECKED_CAST")
            return try {
                sharedGson.fromJson(responseStr, Map::class.java) as? Map<String, Any?>
            } catch (_: Exception) {
                null
            }
        }

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

        fun executeWithFallback(
            project: Project,
            options: RunOptions,
        ): RunResult {
            val settings = DocscribeSettings.getInstance()
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
                noBoilerplate = settings.omitBoilerplate,
            )
        }
    }
}
