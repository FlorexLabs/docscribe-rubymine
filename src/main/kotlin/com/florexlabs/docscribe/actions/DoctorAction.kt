package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeDaemon.DocscribeCapabilities
import com.florexlabs.docscribe.runner.DocscribeDaemon.DocscribeStatus
import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.RbsDetector
import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

/**
 * Diagnose DocScribe plugin setup and show a detailed report.
 *
 * Collects: project root, Gemfile, Ruby SDK, docscribe gem status/version,
 * daemon server state, plugin settings. Calls none of the query methods
 * trigger side effects — they read cached state.
 */
@Suppress("TooManyFunctions")
class DoctorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val report = buildReport(project)
        notify(project, report)
    }

    /**
     * Enable action for any open project.
     */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    /**
     * Always use a background thread for update checks.
     */
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Build a multi-line diagnostic report string.
     *
     * @param project The current project.
     * @return Formatted diagnostic text with sections.
     */
    private fun buildReport(project: Project): String {
        val daemon = DocscribeDaemon.getInstance(project)
        // Ensure version is fresh if Gemfile.lock changed
        try {
            daemon.performGemCheck()
        } catch (_: Exception) {
        }
        val basePath = project.basePath ?: "?"
        val gemRoot = DocscribeRunner.findProjectRoot(basePath)
        val rubyPath = daemon.getRubyPath()
        val status = daemon.getDocscribeStatus()
        val caps = daemon.getCapabilities()

        val lines = mutableListOf("=== DocScribe Diagnostics ===\n")
        lines += projectInfoLines(basePath, gemRoot)
        lines += ""
        lines += rubySdkLines(project, daemon)
        lines += ""
        lines += gemInfoLines(status, caps)
        lines += ""
        lines += rbsInfoLines(gemRoot)
        lines += ""
        lines += daemonInfoLines(project, status, caps)
        lines += ""
        val settings = DocscribeSettings.getInstance()
        lines += listOf("Settings:", "  hideCommentsByDefault = ${settings.hideCommentsByDefault}")
        lines += ""
        lines += issuesSummaryLines(rubyPath, gemRoot, status)
        return lines.joinToString("\n")
    }

    /**
     * Project root and Gemfile status.
     */
    private fun projectInfoLines(
        basePath: String,
        gemRoot: String?,
    ): List<String> {
        val lines = mutableListOf("Project root: $basePath")
        val gemFile = if (gemRoot != null) File(gemRoot, "Gemfile") else null
        val gemStatus =
            when {
                gemFile != null && gemFile.exists() -> "found ($gemFile)"
                gemFile != null -> "Gemfile path exists but file missing: $gemFile"
                else -> "not found (no Gemfile in tree)"
            }
        lines += "Gemfile: $gemStatus"
        return lines
    }

    /**
     * IDE Ruby SDK and resolved Ruby binary path.
     */
    private fun rubySdkLines(
        project: Project,
        daemon: DocscribeDaemon,
    ): List<String> {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val sdkInfo =
            if (sdk != null) "${sdk.name} (home: ${sdk.homePath ?: "?"})" else "not configured"
        val rubyPath = daemon.getRubyPath()
        return listOf("IDE Ruby SDK: $sdkInfo", "Ruby binary: ${rubyPath ?: "not found"}")
    }

    /**
     * docscribe gem availability, version, and server mode support.
     */
    private fun gemInfoLines(
        status: DocscribeStatus,
        caps: DocscribeCapabilities?,
    ): List<String> {
        val lines = mutableListOf("docscribe gem: ${statusLabel(status)}")
        if (status == DocscribeStatus.UNCHECKED) {
            lines += "  (run any DocScribe action to trigger gem detection)"
        }
        if (caps != null) {
            lines += "  Version: ${caps.version}"
            lines += "  Server mode: ${if (caps.serverMode) "supported (>= 1.5.1)" else "not available (< 1.5.1)"}"
        }
        return lines
    }

    /**
     * RBS status: sig dir, rbs gem, collection, docscribe.yml flag.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun rbsInfoLines(gemRoot: String?): List<String> {
        if (gemRoot == null) return listOf("RBS: project root not found")
        val hasSig =
            try {
                val sig = File(gemRoot, "sig")
                sig.isDirectory && sig.walkTopDown().any { it.isFile && it.extension == "rbs" }
            } catch (_: Exception) {
                false
            }
        val sigStatus = if (hasSig) "found (sig/ contains *.rbs)" else "not found (no sig/*.rbs)"
        val rbsInLock =
            try {
                val lock = File(gemRoot, "Gemfile.lock")
                lock.isFile && Regex("""^\s+rbs\s\(""", RegexOption.MULTILINE).containsMatchIn(lock.readText())
            } catch (_: Exception) {
                false
            }
        val rbsInGemfile =
            try {
                val gf = File(gemRoot, "Gemfile")
                gf.isFile && Regex("""gem\s+['"]rbs['"]""").containsMatchIn(gf.readText())
            } catch (_: Exception) {
                false
            }
        val rbsGemStatus =
            when {
                rbsInLock -> "found in Gemfile.lock"
                rbsInGemfile -> "declared in Gemfile (run bundle install)"
                else -> "not found"
            }
        val collection = File(gemRoot, "rbs_collection.lock.yaml").exists()
        val enabled = RbsDetector.shouldUseRbs(gemRoot)
        val lines = mutableListOf("RBS:")
        lines += "  sig/: $sigStatus"
        lines += "  rbs gem: $rbsGemStatus"
        lines += "  rbs_collection.lock.yaml: ${if (collection) "found" else "not found"}"
        lines += "  rbs.enabled: ${if (enabled) "true (RBS types will be used)" else "false (heuristic inference only)"}"
        if (hasSig && !enabled) lines += "  (sig/ exists but rbs.enabled is false → set rbs.enabled: true in docscribe.yml to enable)"
        return lines
    }

    /**
     * Daemon server state and fallback explanation.
     */
    private fun daemonInfoLines(
        project: Project,
        status: DocscribeStatus,
        caps: DocscribeCapabilities?,
    ): List<String> {
        val daemon = DocscribeDaemon.getInstance(project)
        val running = daemon.isServerRunning()
        val lines = mutableListOf("Daemon server: ${if (running) "running" else "stopped"}")
        if (status == DocscribeStatus.AVAILABLE) {
            val shouldRun = caps?.serverMode == true
            when {
                shouldRun && !running -> lines += "  (will start on next DocScribe action)"
                !shouldRun -> lines += "  (server not available — using CLI fallback)"
            }
        } else if (status == DocscribeStatus.MISSING) {
            lines += "  (gem not installed — see 'docscribe gem' section above)"
        }
        return lines
    }

    /**
     * Collect identified issues with actionable steps.
     */
    private fun issuesSummaryLines(
        rubyPath: String?,
        gemRoot: String?,
        status: DocscribeStatus,
    ): List<String> {
        val issues = mutableListOf<String>()
        if (rubyPath == null) issues += "No Ruby SDK configured or found on PATH."
        if (gemRoot == null) issues += "No Gemfile found in project tree."
        if (status == DocscribeStatus.MISSING) {
            issues += "docscribe gem is not installed. Add 'gem \"docscribe\"' to Gemfile and run 'bundle install'."
        }
        return if (issues.isEmpty()) {
            listOf("Status: OK — all systems nominal.")
        } else {
            listOf("Issues found:") + issues.map { "  - $it" }
        }
    }

    /**
     * Human-readable label for a [DocscribeStatus] value.
     */
    private fun statusLabel(status: DocscribeStatus): String =
        when (status) {
            DocscribeStatus.UNCHECKED -> "not yet checked"
            DocscribeStatus.AVAILABLE -> "AVAILABLE"
            DocscribeStatus.MISSING -> "MISSING"
        }

    /**
     * Show a DocScribe information notification.
     *
     * @param project The project to show the notification in.
     * @param content The multi-line diagnostic report.
     */
    private fun notify(
        project: Project,
        content: String,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("DocScribe")
        group.createNotification(content, NotificationType.INFORMATION).notify(project)
    }
}
