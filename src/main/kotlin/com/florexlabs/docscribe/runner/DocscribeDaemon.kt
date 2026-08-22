package com.florexlabs.docscribe.runner

import com.google.gson.GsonBuilder
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.annotations.VisibleForTesting
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * Project-level service managing a long-running docscribe server process over Unix domain socket RPC.
 *
 * ## Gem availability
 * On first use, runs `bundle exec docscribe --version` to verify the gem is installed
 * ([performGemCheck]). The result is cached in [docscribeStatus] for the session.
 * If the gem is missing, a user notification with "Open Gemfile" action is shown once,
 * and all subsequent calls return a descriptive error without retrying.
 *
 * ## Execution
 * Uses JSON-RPC 2.0 over a Unix socket to communicate with a headless docscribe server.
 * Falls back to direct CLI execution ([DocscribeRunner.runDocscribe]) if the server is
 * unavailable or the gem is known missing.
 *
 * ## RPC methods
 * - `check` — dry-run diagnostics.
 * - `check_batch` — dry-run diagnostics for multiple files in one call.
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
     * Whether the `docscribe` gem is available in the project.
     *
     * - [DocscribeStatus.UNCHECKED] — not yet verified (initial state).
     * - [DocscribeStatus.AVAILABLE] — `bundle exec docscribe --version` succeeded.
     * - [DocscribeStatus.MISSING] — gem not found; don't retry this session.
     */
    @Volatile
    @VisibleForTesting
    internal var docscribeStatus = DocscribeStatus.UNCHECKED

    /** True once a "gem not found" notification has been shown this session. */
    @Volatile
    private var missingNotified = false

    /** Tracks whether the `docscribe` gem is installed in the project. */
    enum class DocscribeStatus { UNCHECKED, AVAILABLE, MISSING }

    /**
     * Parsed docscribe version and derived capabilities.
     *
     * Populated by [performGemCheck] when the gem is found.
     * Used by [ensureRunning] to decide whether server mode is available.
     */
    @Volatile
    @VisibleForTesting
    internal var capabilities: DocscribeCapabilities? = null

    /**
     * Capabilities detected from the docscribe version.
     *
     * @property version      Full version string (e.g. `"1.5.1"`).
     * @property serverMode   Server/daemon mode supported (version >= 1.5.1).
     * @property batchMode    `check_batch` RPC supported (version >= 1.5.2).
     */
    data class DocscribeCapabilities(
        val version: String,
        val serverMode: Boolean,
        val batchMode: Boolean = false,
    )

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

    // -- Public diagnostics accessors --

    /**
     * The Ruby executable path resolved for this project.
     *
     * @return Absolute path to Ruby, or `null` if not found.
     */
    fun getRubyPath(): String? = rubyCommand()

    /**
     * Current docscribe gem availability status.
     *
     * @return [DocscribeStatus] — UNCHECKED, AVAILABLE, or MISSING.
     */
    fun getDocscribeStatus(): DocscribeStatus = docscribeStatus

    /**
     * Parsed docscribe capabilities (version + server mode support).
     *
     * @return Capabilities if gem was detected, `null` otherwise.
     */
    fun getCapabilities(): DocscribeCapabilities? = capabilities

    /**
     * Whether the docscribe daemon server is currently running and alive.
     *
     * @return `true` if the server process is active.
     */
    fun isServerRunning(): Boolean = server != null && alive

    // -- RPC execution --

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
     * Execute a `check_batch` RPC against the server, falling back to CLI if needed.
     *
     * Sends all [files] in a single RPC call. When the daemon is unavailable or the installed
     * docscribe version does not support `check_batch` (< 1.5.2), falls back to the directory-scan
     * CLI mode (same as the pre-batch workspace check).
     *
     * @param files      Absolute paths of the Ruby files to check.
     * @param projectDir Optional project root directory.
     * @return The [RunResult] containing an aggregated check JSON (see [buildBatchCheckJson]).
     */
    fun executeBatch(
        files: List<String>,
        projectDir: String? = null,
    ): RunResult {
        synchronized(lock) {
            val handle = ensureRunning(projectDir)
            if (handle == null || capabilities?.batchMode != true) {
                log.info("check_batch not available, using CLI directory scan")
                return fallback("check", file = null, projectDir = projectDir, formatJson = true)
            }
            val params = buildBatchParams(files, projectDir ?: project.basePath ?: "")
            val response = rpcCall(handle, "check_batch", params)
            return processBatchResponse(response, projectDir)
        }
    }

    /**
     * Convert the raw `check_batch` RPC response into an aggregated [RunResult].
     *
     * Per-file results are merged into a single check JSON via [buildBatchCheckJson]:
     * files with `status` `"ok"`/`"fail"` become file entries with their offenses,
     * files with status `"error"` are counted in the summary `error_count`.
     *
     * @param response  The parsed RPC response, or `null`.
     * @param projectDir The project root directory.
     * @return A [RunResult] representing the aggregated batch outcome.
     */
    private fun processBatchResponse(
        response: Map<String, Any?>?,
        projectDir: String?,
    ): RunResult {
        if (response == null) {
            log.warn("Batch RPC returned null, falling back")
            return fallback("check", file = null, projectDir = projectDir, formatJson = true)
        }

        val error = response["error"]
        if (error != null) {
            val msg = (error as? Map<*, *>)?.get("message")?.toString() ?: error.toString()
            return RunResult(success = false, hasIssues = false, exitCode = 1, stdout = "", stderr = "Server error: $msg")
        }

        val result = response["result"] as? Map<*, *>
        val results = result?.get("results") as? List<*> ?: emptyList<Any>()
        val jsonOutput = buildBatchCheckJson(results)
        val parsed = gson.fromJson(jsonOutput, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val parsedMap = parsed as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val files = parsedMap?.get("files") as? List<Map<String, Any?>>
        val totalOffenses = files?.sumOf { f -> (f["offenses"] as? List<*>)?.size ?: 0 } ?: 0

        @Suppress("UNCHECKED_CAST")
        val summary = parsedMap?.get("summary") as? Map<String, Any?>
        val totalErrors = (summary?.get("error_count") as? Number)?.toInt() ?: 0
        return RunResult(
            success = true,
            hasIssues = totalOffenses > 0,
            exitCode = if (totalOffenses > 0 || totalErrors > 0) 1 else 0,
            stdout = jsonOutput,
            stderr = "",
        )
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
    ): Map<String, Any?> {
        val dir = projectDir ?: project.basePath ?: ""
        val map =
            mutableMapOf<String, Any?>(
                "file" to file,
                "project_dir" to dir,
                "no_boilerplate" to true,
            )
        val cliOverrides = buildRbsCliOverrides(dir)
        if (cliOverrides != null) map["cli_overrides"] = cliOverrides
        return map
    }

    private fun buildRbsCliOverrides(projectDir: String): Map<String, Any?>? {
        if (!RbsDetector.shouldUseRbs(projectDir)) return null
        val overrides = mutableMapOf<String, Any?>("rbs" to true)
        if (RbsDetector.hasCollection(projectDir)) overrides["rbs_collection"] = true
        // sig_dirs from default ['sig'] already handled by gem when rbs=true
        return overrides
    }

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
        if (existing != null && alive) {
            if (isServerSocketAlive(existing.socketPath)) return existing
            log.warn("Docscribe server socket is gone, restarting server")
            die()
        }

        // One-time check: is the docscribe gem available?
        if (docscribeStatus == DocscribeStatus.UNCHECKED) {
            performGemCheck()
        }
        if (docscribeStatus == DocscribeStatus.MISSING) {
            return null
        }

        // Versions before 1.5.1 don't support server mode — fall back to CLI
        if (capabilities != null && !capabilities!!.serverMode) {
            log.info("docscribe ${capabilities!!.version} does not support server mode, using CLI")
            return null
        }

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
        env.putAll(buildSdkEnvironment())

        // RubyMine may be launched without a locale set, which makes the server
        // read source files as US-ASCII and fail on non-ASCII content.
        configureLocaleEnv(env)

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
        val group = notificationGroup() ?: return
        group.createNotification(message, NotificationType.ERROR).notify(project)
    }

    private fun notificationGroup(): NotificationGroup? {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        if (group == null) {
            log.warn("Notification group 'DocScribe' is not registered")
        }
        return group
    }

    /**
     * Check whether `bundle exec docscribe --version` succeeds.
     *
     * Caches the result in [docscribeStatus] so the check runs only once per session.
     * Shows a user-friendly notification (with "Open Gemfile" action) on first failure.
     */
    @VisibleForTesting
    internal fun performGemCheck() {
        if (docscribeStatus != DocscribeStatus.UNCHECKED) return
        docscribeStatus = DocscribeStatus.MISSING // pessimistic default

        val gemRoot = DocscribeRunner.findProjectRoot(project.basePath ?: "")
        if (gemRoot == null) {
            if (!missingNotified) showMissingDocscribeNotification(project.basePath ?: "")
            return
        }

        try {
            val pb =
                ProcessBuilder(bundleCommand() ?: "bundle", "exec", "docscribe", "--version")
                    .directory(File(gemRoot))
            pb.environment().putAll(buildSdkEnvironment())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val exited = proc.waitFor(GEM_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                log.warn("docscribe version check timed out after ${GEM_CHECK_TIMEOUT_SECONDS}s")
                return
            }
            if (proc.exitValue() == 0) {
                docscribeStatus = DocscribeStatus.AVAILABLE
                val versionOutput =
                    proc.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                capabilities = parseVersion(versionOutput)
                log.info("docscribe gem detected (version: ${capabilities?.version ?: versionOutput})")
            } else {
                val output = proc.inputStream.bufferedReader().readText()
                log.warn("docscribe version check failed: exit ${proc.exitValue()}, output: $output")
            }
        } catch (e: IOException) {
            log.warn("Failed to run docscribe version check", e)
        }

        if (docscribeStatus == DocscribeStatus.MISSING && !missingNotified) {
            showMissingDocscribeNotification(gemRoot)
        }
    }

    /**
     * Show a one-time error notification that the docscribe gem is missing,
     * with a quick-action to open the project Gemfile.
     */
    private fun showMissingDocscribeNotification(gemRoot: String) {
        missingNotified = true
        val group = notificationGroup()
        if (group == null) {
            log.warn("Cannot show missing-docscribe notification: group not registered")
            return
        }
        val message =
            "docscribe gem not found in project Gemfile. " +
                "Add 'gem \"docscribe\"' and run 'bundle install'."
        val notification =
            group
                .createNotification(message, NotificationType.ERROR)
        notification.addAction(
            object : AnAction("Open Gemfile") {
                override fun actionPerformed(e: AnActionEvent) {
                    val gemFile = File(gemRoot, "Gemfile")
                    if (gemFile.exists()) {
                        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(gemFile)
                        if (vFile != null) {
                            FileEditorManager.getInstance(project).openFile(vFile, true)
                        }
                    }
                }
            },
        )
        notification.notify(project)
    }

    /**
     * Resolve the Ruby executable path, preferring the project SDK if configured.
     *
     * Falls back to the first module SDK (Ruby projects often configure the SDK
     * per-module only), then to `PATH` lookup and `~/.rbenv/shims/ruby`.
     *
     * @return Path to the Ruby executable, or `null` if none is found.
     */
    private fun rubyCommand(): String? {
        val sdk = resolveRubySdk()
        val homePath = sdk?.homePath
        if (homePath != null) {
            if (homePath.endsWith("ruby") && File(homePath).canExecute()) {
                return homePath
            }
            val rubyPath = "$homePath/bin/ruby"
            if (File(rubyPath).canExecute()) return rubyPath
            log.warn("Ruby SDK configured but binary not found at $rubyPath, falling back to PATH")
        }

        val rubyFromPath = findRubyOnPath()
        if (rubyFromPath != null) return rubyFromPath

        log.warn("No Ruby found on PATH or via SDK")
        return null
    }

    /**
     * Resolve the project's Ruby SDK.
     *
     * Tries the project-level SDK first, then falls back to the SDK of any
     * module, preferring one whose home path looks like a Ruby installation.
     */
    private fun resolveRubySdk(): Sdk? {
        val projSdk = ProjectRootManager.getInstance(project).projectSdk
        val moduleSdks =
            ModuleManager
                .getInstance(project)
                .modules
                .mapNotNull { ModuleRootManager.getInstance(it).sdk }
        val chosenHome = resolveRubyHome(projSdk?.homePath, moduleSdks.map { it.homePath })
        val chosen =
            when {
                chosenHome == null -> null
                projSdk?.homePath == chosenHome -> projSdk
                else -> moduleSdks.firstOrNull { it.homePath == chosenHome }
            }
        if (chosen != null) {
            log.info(
                "DocScribe: Ruby SDK from ${if (projSdk?.homePath == chosenHome) "project" else "module"}: " +
                    "'${chosen.name}' home=${chosen.homePath}",
            )
        } else {
            log.info("DocScribe: no project or module SDK found, falling back to PATH")
        }
        return chosen
    }

    /**
     * Absolute path to the SDK's `bundle` executable, or `null` to rely on `PATH`.
     *
     * The JVM on macOS resolves bare executable names against its own copy of
     * `PATH`, which the [buildSdkEnvironment] override does not reliably affect;
     * using the absolute path guarantees the project's Ruby SDK is used.
     */
    private fun bundleCommand(): String? = bundlePathFor(rubyCommand())

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
     * Build environment variables that point `bundle` and `ruby` to the project's Ruby SDK.
     *
     * Prepends the SDK's `bin/` directory to `PATH` and sets `BUNDLE_GEMFILE`.
     * Returns an empty map when no SDK is configured or the SDK binary is not found.
     */
    private fun buildSdkEnvironment(): Map<String, String> {
        val ruby = rubyCommand() ?: return emptyMap()
        val sdkBin = File(ruby).parentFile?.absolutePath ?: return emptyMap()
        val currentPath = System.getenv("PATH") ?: ""
        val env = mutableMapOf("PATH" to "$sdkBin${File.pathSeparator}$currentPath")
        val gemRoot = DocscribeRunner.findProjectRoot(project.basePath ?: "")
        if (gemRoot != null) {
            env["BUNDLE_GEMFILE"] = File(gemRoot, "Gemfile").absolutePath
        }
        log.info("DocScribe: gem check env: ruby=$ruby, BUNDLE_GEMFILE=${env["BUNDLE_GEMFILE"]}, PATH=${env["PATH"]}")
        return env
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
        if (docscribeStatus == DocscribeStatus.MISSING) {
            return RunResult(
                success = false,
                hasIssues = false,
                exitCode = 2,
                stdout = "",
                stderr =
                    "docscribe gem is not installed. " +
                        "Add 'gem \"docscribe\"' to your Gemfile and run 'bundle install'.",
            )
        }
        val strategy = strategyFromCommand(command)
        val options =
            RunOptions(
                projectDir = projectDir ?: project.basePath ?: "",
                file = file,
                strategy = strategy,
                formatJson = formatJson,
                bundlePath = bundleCommand(),
            )
        val sdkEnv = buildSdkEnvironment()
        val executor = if (sdkEnv.isEmpty()) DefaultCommandExecutor() else DefaultCommandExecutor(sdkEnv)
        return DocscribeRunner.runDocscribe(options, executor)
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
        private const val GEM_CHECK_TIMEOUT_SECONDS = 10L
        private const val BATCH_PER_FILE_TIMEOUT_SECONDS = 120L
        private const val SERVER_MODE_MIN_VERSION = "1.5.1"
        private const val BATCH_MODE_MIN_VERSION = "1.5.2"
        private val sharedGson by lazy { GsonBuilder().create() }

        /**
         * Build the parameters map sent in a `check_batch` RPC request.
         *
         * @param files          Absolute paths of the files to check.
         * @param projectDir     Project root directory.
         * @param timeoutSeconds Per-file timeout in seconds.
         * @return A map of RPC parameters.
         */
        @JvmStatic
        fun buildBatchParams(
            files: List<String>,
            projectDir: String,
            timeoutSeconds: Long = BATCH_PER_FILE_TIMEOUT_SECONDS,
        ): Map<String, Any?> {
            val map =
                mutableMapOf<String, Any?>(
                    "files" to files,
                    "project_dir" to projectDir,
                    "no_boilerplate" to true,
                    "timeout" to timeoutSeconds,
                )
            val cliOverrides = buildRbsCliOverridesStatic(projectDir)
            if (cliOverrides != null) map["cli_overrides"] = cliOverrides
            return map
        }

        @JvmStatic
        internal fun buildRbsCliOverridesStatic(projectDir: String): Map<String, Any?>? {
            if (!RbsDetector.shouldUseRbs(projectDir)) return null
            val overrides = mutableMapOf<String, Any?>("rbs" to true)
            if (RbsDetector.hasCollection(projectDir)) overrides["rbs_collection"] = true
            return overrides
        }

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
            val offenses = changesToOffenses(changes)
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
         * Build an aggregated check JSON from a `check_batch` server response.
         *
         * Each batch result must be a map with:
         * - `"file"` — the absolute file path;
         * - `"status"` — `"ok"`, `"fail"`, or `"error"`;
         * - `"changes"` — list of changes (ignored for `"error"` status);
         * - `"error"` — error message (only for `"error"` status, unused in output).
         *
         * Files with status `"ok"`/`"fail"` become file entries with their offenses;
         * files with status `"error"` are counted in the summary `error_count`.
         * Non-map entries are skipped.
         *
         * @param results The `results` array from the `check_batch` response.
         * @return A JSON string compatible with [DocscribeOutputParser.parseJson].
         */
        fun buildBatchCheckJson(results: List<*>): String {
            val gsonLocal = GsonBuilder().create()
            val files = mutableListOf<Map<String, Any>>()
            var offenseCount = 0
            var errorCount = 0
            var targetCount = 0

            val validResults =
                results.mapNotNull { result ->
                    if (result is Map<*, *>) {
                        (result["file"] as? String)?.let { filePath -> filePath to result }
                    } else {
                        null
                    }
                }

            for ((filePath, result) in validResults) {
                targetCount++
                val status = result["status"] as? String ?: "error"
                if (status == "error") {
                    errorCount++
                    continue
                }
                val changes = result["changes"] as? List<*> ?: emptyList<Any>()
                val offenses = changesToOffenses(changes)
                offenseCount += offenses.size
                files.add(mapOf("path" to filePath, "offenses" to offenses))
            }

            val output =
                mapOf(
                    "metadata" to mapOf("docscribe_version" to "1.5.1"),
                    "files" to files,
                    "summary" to
                        mapOf(
                            "offense_count" to offenseCount,
                            "target_file_count" to targetCount,
                            "inspected_file_count" to files.size,
                            "error_count" to errorCount,
                        ),
                )
            return gsonLocal.toJson(output)
        }

        /**
         * Convert a list of server changes into offense maps.
         *
         * Each change is mapped to an offense with severity `convention`, cop name
         * `DocScribe/MissingDocumentation`, and the change's line number (default 1).
         * Non-map elements are skipped.
         *
         * @param changes The list of changes from the server response.
         * @return A list of offense maps.
         */
        private fun changesToOffenses(changes: List<*>): List<Map<String, Any>> =
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

        /**
         * Parse docscribe version from `--version` output.
         *
         * Handles versions like `1.5.0`, `1.5.1`, or `1.5.0\n` (trailing newline).
         * Returns `null` for unparseable output (treated as unknown version, server mode disabled).
         *
         * @param versionOutput The trimmed stdout from `bundle exec docscribe --version`.
         * @return [DocscribeCapabilities] with server mode enabled for version >= 1.5.1
         *   and batch mode enabled for version >= 1.5.2.
         */
        @JvmStatic
        fun parseVersion(versionOutput: String): DocscribeCapabilities? {
            val version = versionOutput.trim().takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) } ?: return null
            val parts = version.split(".").map { it.toIntOrNull() ?: return null }
            val serverMode = parts.size == 3 && atLeast(parts, SERVER_MODE_MIN_VERSION)
            val batchMode = parts.size == 3 && atLeast(parts, BATCH_MODE_MIN_VERSION)
            return DocscribeCapabilities(version = version, serverMode = serverMode, batchMode = batchMode)
        }

        /**
         * Compare a parsed version triple against a target version.
         *
         * @param parts         The parsed version parts `[major, minor, patch]`.
         * @param targetVersion Target version string like `"1.5.1"`.
         * @return `true` if the parsed version is greater than or equal to the target.
         */
        private fun atLeast(
            parts: List<Int>,
            targetVersion: String,
        ): Boolean {
            val target = targetVersion.split(".").map { it.toInt() }
            return parts[0] > target[0] ||
                (parts[0] == target[0] && parts[1] > target[1]) ||
                (parts[0] == target[0] && parts[1] == target[1] && parts[2] >= target[2])
        }

        /**
         * Execute a workspace-wide check via `check_batch`, falling back to CLI if the server
         * or batch support is unavailable.
         *
         * Convenience wrapper that resolves the [DocscribeDaemon] instance and delegates to
         * [executeBatch].
         *
         * @param project    The current project.
         * @param files      Absolute paths of the Ruby files to check.
         * @param projectDir The project root directory.
         * @return The [RunResult] from the execution.
         */
        fun executeBatchWithFallback(
            project: Project,
            files: List<String>,
            projectDir: String,
        ): RunResult {
            val daemon = getInstance(project)
            return daemon.executeBatch(files, projectDir)
        }

        /**
         * Choose the Ruby home path to use for docscribe invocations.
         *
         * Priority: project-level SDK home, then the first module SDK home whose path
         * looks like a Ruby installation (ends with `ruby`), then any module SDK home.
         *
         * @param projectHome The project-level SDK home path, or `null`.
         * @param moduleHomes Home paths of all module-level SDKs, in module order.
         * @return The chosen home path, or `null` when no SDK is configured.
         */
        @JvmStatic
        fun resolveRubyHome(
            projectHome: String?,
            moduleHomes: List<String?>,
        ): String? {
            if (!projectHome.isNullOrBlank()) return projectHome
            moduleHomes.firstOrNull { it?.endsWith("ruby") == true }?.let { return it }
            return moduleHomes.firstOrNull { !it.isNullOrBlank() }
        }

        /**
         * Absolute path to the `bundle` executable next to the given Ruby binary.
         *
         * The JVM on macOS resolves bare executable names against its own copy of
         * `PATH`, which a `PATH` environment override does not reliably affect; the
         * absolute path guarantees the project's Ruby SDK is used.
         *
         * @param rubyPath Path to the Ruby executable (SDK `homePath`), or `null`.
         * @return Absolute path to `bundle` when it exists and is executable, else `null`.
         */
        @JvmStatic
        fun bundlePathFor(rubyPath: String?): String? {
            if (rubyPath.isNullOrBlank()) return null
            val bundle = File(File(rubyPath).parentFile, "bundle")
            return if (bundle.canExecute()) bundle.absolutePath else null
        }
    }
}

/**
 * Check whether a previously started server socket file still exists.
 *
 * The daemon exits after its idle timeout and removes the socket file, so
 * `alive` alone is not sufficient — a stale handle must trigger a restart.
 *
 * @param socketPath The socket file to validate.
 * @return `true` if the socket file still exists.
 */
internal fun isServerSocketAlive(socketPath: Path): Boolean = Files.exists(socketPath)

/**
 * Force a UTF-8 locale on the server environment.
 *
 * RubyMine may be launched without a locale set, which makes Ruby read
 * source files as US-ASCII and fail on non-ASCII content. Falls back to
 * `en_US.UTF-8` when `LANG` is unset or blank.
 *
 * @param env        The environment map to configure (mutated in place).
 * @param systemLang The `LANG` value from the system, or `null` if unset.
 */
internal fun configureLocaleEnv(
    env: MutableMap<String, String>,
    systemLang: String? = System.getenv("LANG"),
) {
    val lang = systemLang?.takeIf { it.isNotBlank() } ?: "en_US.UTF-8"
    env["LANG"] = lang
    env["LC_ALL"] = lang
}
