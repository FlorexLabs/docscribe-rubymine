package com.florexlabs.docscribe.mcp

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.Serializable

/**
 * MCP toolset exposing DocScribe actions for API testing via JetBrains MCP.
 * Registered in `withMcpServer.xml` as `mcpServer.mcpToolset`.
 * Each `suspend` function becomes a tool (snake_case name → tool name).
 */
@Suppress(
    "RedundantSuspendModifier",
    "unused",
    "FunctionNaming",
    "TooGenericExceptionCaught",
    "MaxLineLength",
    "CyclomaticComplexMethod",
    "LongMethod",
    "ktlint:standard:function-naming",
)
class DocScribeMcpToolset : McpToolset {
    override fun isEnabled(): Boolean {
        // Only enable when at least one open project looks like a DocScribe project (has Gemfile with docscribe)
        // This prevents the toolset from being instantiated in unrelated projects and in tests without a project
        return try {
            ProjectManager.getInstance().openProjects.any { p ->
                val base = p.basePath ?: return@any false
                val gemfile = java.io.File(base, "Gemfile")
                gemfile.isFile && gemfile.readText().contains("docscribe")
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Serializable
    data class CheckResult(
        val projectPath: String?,
        val filePath: String?,
        val success: Boolean,
        val hasIssues: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    @Serializable
    data class FixResult(
        val projectPath: String?,
        val filePath: String?,
        val success: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    @Serializable
    data class DoctorResult(
        val projectPath: String?,
        val report: String,
    )

    private fun findProject(
        projectPath: String?,
        filePath: String? = null,
    ): Project? {
        val open =
            try {
                ProjectManager.getInstance().openProjects
            } catch (_: Throwable) {
                return null
            }
        if (projectPath != null) {
            open.find { it.basePath == projectPath }?.let { return it }
            // also try by canonical file
            open.find { projectPath.startsWith(it.basePath ?: "") }?.let { return it }
        }
        if (filePath != null) {
            open.find { filePath.startsWith(it.basePath ?: "") }?.let { return it }
        }
        return open.firstOrNull()
    }

    private fun resolveProjectPath(
        project: Project,
        projectPath: String?,
    ): String = projectPath ?: project.basePath ?: ""

    @McpTool
    @McpDescription(
        "Run docscribe check on a Ruby file and return diagnostics (YARD mismatches, missing docs). Use for testing Check Current File.",
    )
    suspend fun docscribe_check_file(
        @McpDescription("Absolute path to Ruby file")
        filePath: String,
        @McpDescription("Absolute project root. If null, uses first open project.")
        projectPath: String? = null,
    ): CheckResult {
        val project =
            findProject(projectPath, filePath)
                ?: return CheckResult(projectPath, filePath, false, false, 2, "", "No open project found for $projectPath / $filePath")
        val pPath = resolveProjectPath(project, projectPath)
        val daemon = project.getService(DocscribeDaemon::class.java)
        val run = daemon.execute("check", file = filePath, projectDir = pPath, formatJson = true)
        return CheckResult(pPath, filePath, run.success, run.hasIssues, run.exitCode, run.stdout, run.stderr)
    }

    @McpTool
    @McpDescription("Run docscribe check on all Ruby files in the project (Check Entire Workspace).")
    suspend fun docscribe_check_workspace(
        @McpDescription("Absolute project root. If null, uses first open project.")
        projectPath: String? = null,
    ): CheckResult {
        val project =
            findProject(projectPath)
                ?: return CheckResult(projectPath, null, false, false, 2, "", "No open project")
        val pPath = resolveProjectPath(project, projectPath)
        val daemon = project.getService(DocscribeDaemon::class.java)
        return try {
            val files =
                com.florexlabs.docscribe.actions
                    .collectRubyFiles(project, pPath)
            if (files.isEmpty()) {
                return CheckResult(
                    pPath,
                    null,
                    true,
                    false,
                    0,
                    """{"files":[],"summary":{"offense_count":0,"inspected_file_count":0}}""",
                    "",
                )
            }
            val run = daemon.executeBatch(files, pPath)
            CheckResult(pPath, null, run.success, run.hasIssues, run.exitCode, run.stdout, run.stderr)
        } catch (e: Exception) {
            CheckResult(pPath, null, false, false, 2, "", e.message ?: "error")
        }
    }

    @McpTool
    @McpDescription("Apply docscribe safe fixes to a Ruby file (Apply Safe Fixes).")
    suspend fun docscribe_safe_fix(
        @McpDescription("Absolute path to Ruby file")
        filePath: String,
        @McpDescription("Absolute project root. If null, uses project of file.")
        projectPath: String? = null,
    ): FixResult {
        val project =
            findProject(projectPath, filePath)
                ?: return FixResult(projectPath, filePath, false, 2, "", "No project")
        val pPath = resolveProjectPath(project, projectPath)
        val daemon = project.getService(DocscribeDaemon::class.java)
        val run = daemon.execute("safe_fix", file = filePath, projectDir = pPath, formatJson = false)
        // Refresh VFS as actions do
        try {
            val vFile =
                com.intellij.openapi.vfs.LocalFileSystem
                    .getInstance()
                    .findFileByPath(filePath)
            vFile?.refresh(false, false)
        } catch (_: Exception) {
        }
        return FixResult(pPath, filePath, run.success, run.exitCode, run.stdout, run.stderr)
    }

    @McpTool
    @McpDescription("Apply docscribe aggressive fixes to a Ruby file (Apply Aggressive Fixes).")
    suspend fun docscribe_aggressive_fix(
        @McpDescription("Absolute path to Ruby file")
        filePath: String,
        @McpDescription("Absolute project root. If null, uses project of file.")
        projectPath: String? = null,
    ): FixResult {
        val project =
            findProject(projectPath, filePath)
                ?: return FixResult(projectPath, filePath, false, 2, "", "No project")
        val pPath = resolveProjectPath(project, projectPath)
        val daemon = project.getService(DocscribeDaemon::class.java)
        val run = daemon.execute("aggressive_fix", file = filePath, projectDir = pPath, formatJson = false)
        try {
            val vFile =
                com.intellij.openapi.vfs.LocalFileSystem
                    .getInstance()
                    .findFileByPath(filePath)
            vFile?.refresh(false, false)
        } catch (_: Exception) {
        }
        return FixResult(pPath, filePath, run.success, run.exitCode, run.stdout, run.stderr)
    }

    @McpTool
    @McpDescription("Refresh YARD docs from RBS signatures (Update Types from RBS).")
    suspend fun docscribe_update_types(
        @McpDescription("Absolute project root. If null, uses first open project.")
        projectPath: String? = null,
    ): FixResult {
        val project =
            findProject(projectPath)
                ?: return FixResult(projectPath, null, false, 2, "", "No project")
        val pPath = resolveProjectPath(project, projectPath)
        val daemon = project.getService(DocscribeDaemon::class.java)
        val run = daemon.execute("update_types", file = null, projectDir = pPath, formatJson = false)
        return FixResult(pPath, null, run.success, run.exitCode, run.stdout, run.stderr)
    }

    @McpTool
    @McpDescription("Diagnose DocScribe setup for a project (DocScribe Doctor) — Ruby SDK, gem, daemon, RBS.")
    suspend fun docscribe_doctor(
        @McpDescription("Absolute project root. If null, uses first open project.")
        projectPath: String? = null,
    ): DoctorResult {
        val project =
            findProject(projectPath)
                ?: return DoctorResult(projectPath, "No open project for $projectPath")
        // Reuse DoctorAction logic (duplicated to avoid making buildReport public)
        val daemon = project.getService(DocscribeDaemon::class.java)
        val basePath = project.basePath ?: "?"
        val gemRoot = DocscribeRunner.findProjectRoot(basePath)
        val rubyPath = daemon.getRubyPath()
        val status = daemon.getDocscribeStatus()
        val caps = daemon.getCapabilities()
        // Build report similar to DoctorAction
        val lines = mutableListOf<String>()
        lines += "=== DocScribe Diagnostics (MCP) ==="
        lines += "Project root: $basePath"
        val gemFile = gemRoot?.let { java.io.File(it, "Gemfile") }
        lines += "Gemfile: " +
            when {
                gemFile != null && gemFile.exists() -> "found ($gemFile)"
                gemFile != null -> "Gemfile path exists but file missing: $gemFile"
                else -> "not found (no Gemfile in tree)"
            }
        val sdk =
            com.intellij.openapi.roots.ProjectRootManager
                .getInstance(project)
                .projectSdk
        lines += "IDE Ruby SDK: " + if (sdk != null) "${sdk.name} (home: ${sdk.homePath ?: "?"})" else "not configured"
        lines += "Ruby binary: ${rubyPath ?: "not found"}"
        lines += "docscribe gem: ${status.name}"
        caps?.let {
            lines += "  Version: ${it.version}"
            lines += "  Server mode: ${if (it.serverMode) "supported (>= 1.5.1)" else "not available (< 1.5.1)"}"
            lines += "  Batch mode: ${if (it.batchMode) "supported (>= 1.5.2)" else "not available"}"
        }
        // RBS section (same as DoctorAction rbsInfoLines)
        if (gemRoot != null) {
            val hasSig =
                try {
                    val sig = java.io.File(gemRoot, "sig")
                    sig.isDirectory &&
                        sig.walkTopDown().any { f -> f.isFile && f.extension == "rbs" }
                } catch (_: Exception) {
                    false
                }
            lines += "RBS: sig/ ${if (hasSig) "found" else "not found"}"
            val rbsInLock =
                try {
                    val f = java.io.File(gemRoot, "Gemfile.lock")
                    f.isFile &&
                        Regex("""^\s+rbs\s\(""", RegexOption.MULTILINE).containsMatchIn(f.readText())
                } catch (_: Exception) {
                    false
                }
            val rbsInGemfile =
                try {
                    val f = java.io.File(gemRoot, "Gemfile")
                    f.isFile &&
                        Regex("""gem\s+['"]rbs['"]""").containsMatchIn(f.readText())
                } catch (_: Exception) {
                    false
                }
            lines += "  rbs gem: " +
                when {
                    rbsInLock -> "found in Gemfile.lock"
                    rbsInGemfile -> "declared in Gemfile"
                    else -> "not found"
                }
            lines +=
                "  rbs_collection.lock.yaml: ${if (java.io.File(gemRoot, "rbs_collection.lock.yaml").exists()) "found" else "not found"}"
            val enabled =
                com.florexlabs.docscribe.runner.RbsDetector
                    .shouldUseRbs(gemRoot)
            lines += "  rbs.enabled: $enabled"
        }
        lines += "Daemon server: ${if (daemon.isServerRunning()) "running" else "stopped"}"
        // Use ApplicationManager to get settings
        try {
            val hide =
                com.florexlabs.docscribe.settings.DocscribeSettings
                    .getInstance()
                    .hideCommentsByDefault
            lines += "Settings: hideCommentsByDefault=$hide"
        } catch (_: Exception) {
        }
        return DoctorResult(projectPath ?: basePath, lines.joinToString("\n"))
    }
}
