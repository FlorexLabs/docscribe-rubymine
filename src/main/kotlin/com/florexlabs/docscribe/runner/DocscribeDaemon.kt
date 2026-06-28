package com.florexlabs.docscribe.runner

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

/**
 * Project-level service managing a long-running docscribe server process over Unix domain socket RPC.
 *
 * Uses JSON-RPC 2.0 over a Unix socket to communicate with a headless docscribe server.
 * Falls back to direct CLI execution ([DocscribeRunner.runDocscribe]) if the server is unavailable.
 *
 * Supported RPC methods:
 * - `check` — dry-run diagnostics.
 * - `fix` — with `"strategy": "safe"` or `"aggressive"`.
 * - `ping` — health check.
 * - `update_types` — refresh YARD docs from RBS signatures.
 * - `shutdown` — graceful server stop.
 */
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

    /**
     * Internal handle holding the server process and its Unix socket path.
     *
     * @property socketPath Path to the Unix domain socket file.
     * @property process    The running server process.
     */
    private data class ServerHandle(
        val socketPath: Path,
        val process: Process,
    )

    /**
     * Execute an RPC command against the server, falling back to CLI if needed.
     *
     * @param command    The RPC method name (`check`, `safe_fix`, `aggressive_fix`, `ping`, `update_types`).
     * @param file       Optional path to a specific file to target.
     * @param projectDir Optional project root directory.
     * @param formatJson Whether to request JSON output (check only).
     * @return The [RunResult] from the execution.
     */
    fun execute(
        command: String,
        file: String? = null,
        projectDir: String? = null,
        formatJson: Boolean = false,
    ): RunResult {
        synchronized(lock) {
            val handle = ensureRunning(projectDir) ?: return fallback(command, file, projectDir, formatJson)
            val params = buildExecuteParams(file, projectDir)
            val response = performRpcCall(handle, command, params)
            return processRpcResponse(response, command, file, projectDir, formatJson)
        }
    }

    /**
     * Build the parameters map sent in an RPC request.
     *
     * @param file       Optional specific file to target.
     * @param projectDir Optional project root directory.
     * @return A mutable map of RPC parameters.
     */
    private fun buildExecuteParams(
        file: String?,
        projectDir: String?,
    ): Map<String, Any?> =
        mutableMapOf<String, Any?>(
            "file" to file,
            "project_dir" to (projectDir ?: project.basePath ?: ""),
            "no_boilerplate" to true,
        )

    /**
     * Route [command] to the appropriate RPC method and execute it.
     *
     * @param handle  The server handle with the socket path.
     * @param command The logical command name.
     * @param params  The parameters to pass with the RPC request.
     * @return The parsed RPC response, or `null` on failure.
     */
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

    /**
     * Convert the raw RPC response into a [RunResult].
     *
     * Handles error responses, null responses (fallback to CLI), check results
     * (which are converted to the JSON format via [processCheckResult]), and fix results.
     *
     * @param response  The parsed RPC response, or `null`.
     * @param command   The command that was executed.
     * @param file      The file that was targeted.
     * @param projectDir The project root directory.
     * @param formatJson Whether JSON output was requested.
     * @return A [RunResult] representing the outcome.
     */
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

    /**
     * Convert a server "changes" list into the same JSON format used by check mode.
     *
     * The server returns a list of changes (each with a `line`), which is converted into
     * structured JSON matching [DocscribeOutputParser.parseJson] input format.
     *
     * @param result The raw result from the server (expected to be a map with a `"changes"` list).
     * @param file   The file path to associate with the offenses.
     * @return A [RunResult] containing the JSON-formatted stdout.
     */
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

    /**
     * Map a logical command name back to a [DocscribeStrategy].
     *
     * @param command The logical command name (`safe_fix`, `aggressive_fix`, or anything else).
     * @return The corresponding [DocscribeStrategy].
     */
    private fun strategyFromCommand(command: String): DocscribeStrategy =
        when (command) {
            "safe_fix" -> DocscribeStrategy.SAFE
            "aggressive_fix" -> DocscribeStrategy.AGGRESSIVE
            else -> DocscribeStrategy.CHECK
        }

    /**
     * Ensure the docscribe server is running, starting it if necessary.
     *
     * @param projectDir Optional project root to use when searching for the Ruby SDK.
     * @return A [ServerHandle] if the server is running, or `null` to trigger CLI fallback.
     */
    private fun ensureRunning(projectDir: String?): ServerHandle? {
        val existing = server
        if (existing != null && alive) return existing

        val ruby = rubyCommand()
        val gemRoot = if (ruby != null) DocscribeRunner.findProjectRoot(projectDir ?: project.basePath ?: "") else null
        val proc = if (gemRoot != null) startServerProcess(ruby!!, gemRoot) else null
        val output = if (proc != null) readServerStartupOutput(proc) else null
        return if (output != null) resolveServerHandle(proc!!, output.first, output.second) else null
    }

    /**
     * Read the server's stdout for the socket path and validate the process.
     *
     * @param proc           The running server process.
     * @param socketPathLine The first line read from the process stdout (expected to be the socket path).
     * @param stderrText     The full stderr output (used for error messages).
     * @return A [ServerHandle] if successful, or `null` if the server failed to start.
     */
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

    /**
     * Start the docscribe server as a child process.
     *
     * Uses `ruby -e` to load `docscribe/server` and call `Docscribe::Server.ensure_running!`.
     * Sets `BUNDLE_GEMFILE` and `PATH` from the Ruby SDK if available.
     * Supports a `docscribe.local.gem.path` system property for local development.
     *
     * @param ruby    Path to the Ruby executable.
     * @param gemRoot The project root directory (Gemfile location).
     * @return The started [Process], or `null` on failure.
     */
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

    /**
     * Read the server process stdout (socket path) and wait for it to exit or time out.
     *
     * @param proc The running server process.
     * @return A pair of (socketPathLine, stderrText), or `null` on timeout.
     */
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

    /**
     * Show a DocScribe error notification balloon.
     *
     * @param message The notification message text.
     */
    private fun showNotification(message: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(message, NotificationType.ERROR).notify(project)
    }

    /**
     * Resolve the Ruby executable path, preferring the project SDK if configured.
     *
     * Falls back to `PATH` lookup, then to `~/.rbenv/shims/ruby`.
     *
     * @return Path to the Ruby executable, or `null` if none is found.
     */
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

    /**
     * Search for Ruby on `PATH` or in common version manager locations.
     *
     * Checks `rbenv` shims first, then runs `which ruby` with a short timeout.
     *
     * @return Path to the Ruby executable, or `null`.
     */
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

    /**
     * Perform a single JSON-RPC 2.0 call over a Unix domain socket.
     *
     * Serializes the request, writes it to the socket, reads the response, and parses it.
     *
     * @param handle The server handle with the socket path.
     * @param method The RPC method name.
     * @param params The RPC parameters map.
     * @return The parsed response map, or `null` on I/O error.
     */
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

    /**
     * Fall back to direct CLI execution when the server is unavailable.
     *
     * @param command    The logical command name (maps to a [DocscribeStrategy]).
     * @param file       Optional specific file to target.
     * @param projectDir Optional project root directory.
     * @param formatJson Whether JSON output was requested.
     * @return The [RunResult] from [DocscribeRunner.runDocscribe].
     */
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

    /**
     * Mark the server as dead and clear the handle.
     */
    private fun die() {
        alive = false
        server = null
    }

    /**
     * Shut down the server gracefully on service disposal.
     *
     * Sends a `shutdown` RPC call, then clears the handle. Errors during shutdown are silently ignored.
     */
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

        /**
         * Build a JSON-RPC 2.0 request string.
         *
         * The result is a single-line JSON object followed by a newline, suitable for sending
         * over a Unix domain socket.
         *
         * @param method The RPC method name.
         * @param params The RPC parameters map (may be empty).
         * @return A JSON-RPC request string ending with `\n`.
         */
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

        /**
         * Parse a JSON-RPC 2.0 response string into a map.
         *
         * Returns `null` for blank input or malformed JSON.
         *
         * @param responseStr The raw response string from the server.
         * @return The parsed response map, or `null`.
         */
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

        /**
         * Build a JSON string that matches the docscribe check JSON format from a list of server changes.
         *
         * Each change is converted to an offense entry with severity `convention`,
         * cop name `DocScribe/MissingDocumentation`, and the change's line number.
         *
         * @param filePath The file path to associate with the offenses.
         * @param changes  The list of changes from the server response.
         * @return A JSON string compatible with [DocscribeOutputParser.parseJson].
         */
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

        /**
         * Get the [DocscribeDaemon] instance for the given project.
         *
         * @param project The current project.
         * @return The project-level [DocscribeDaemon] service instance.
         */
        @JvmStatic
        fun getInstance(project: Project): DocscribeDaemon = project.getService(DocscribeDaemon::class.java)

        /**
         * Map [RunOptions] to a logical server command name.
         *
         * Priority: subcommand (`"update_types"`) > strategy (`safe_fix`, `aggressive_fix`, `check`).
         *
         * @param options The run options.
         * @return The logical command name for the server.
         */
        fun commandFromOptions(options: RunOptions): String =
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

        /**
         * Execute docscribe via the daemon, falling back to CLI if the server is unavailable.
         *
         * Convenience wrapper that resolves the [DocscribeDaemon] instance, maps options to a command,
         * and delegates to [execute].
         *
         * @param project The current project.
         * @param options The run options.
         * @return The [RunResult] from the execution.
         */
        fun executeWithFallback(
            project: Project,
            options: RunOptions,
        ): RunResult {
            val daemon = getInstance(project)
            val command = commandFromOptions(options)
            return daemon.execute(
                command = command,
                file = options.file,
                projectDir = options.projectDir.let { d -> DocscribeRunner.findProjectRoot(d) ?: d },
                formatJson = options.formatJson,
            )
        }
    }
}
