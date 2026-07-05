package com.florexlabs.docscribe.actions

import com.florexlabs.docscribe.runner.DocscribeDaemon
import com.florexlabs.docscribe.runner.DocscribeDaemon.DocscribeStatus
import com.florexlabs.docscribe.runner.DocscribeRunner
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
        val lines = mutableListOf("=== DocScribe Diagnostics ===\n")

        // --- Project ---
        val basePath = project.basePath ?: "?"
        lines += "Project root: $basePath"
        val gemRoot = DocscribeRunner.findProjectRoot(basePath)
        val gemFile = if (gemRoot != null) File(gemRoot, "Gemfile") else null
        val gemStatus =
            when {
                gemFile != null && gemFile.exists() -> "found ($gemFile)"
                gemFile != null -> "Gemfile path exists but file missing: $gemFile"
                else -> "not found (no Gemfile in tree)"
            }
        lines += "Gemfile: $gemStatus"

        // --- Ruby SDK ---
        lines += ""
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val sdkInfo =
            if (sdk != null) {
                "${sdk.name} (home: ${sdk.homePath ?: "?"})"
            } else {
                "not configured"
            }
        lines += "IDE Ruby SDK: $sdkInfo"

        val daemon = DocscribeDaemon.getInstance(project)
        val rubyPath = daemon.getRubyPath()
        lines += "Ruby binary: ${rubyPath ?: "not found"}"

        // --- docscribe gem ---
        lines += ""
        val status = daemon.getDocscribeStatus()
        lines += "docscribe gem: ${statusLabel(status)}"
        if (status == DocscribeStatus.UNCHECKED) {
            lines += "  (run any DocScribe action to trigger gem detection)"
        }

        val caps = daemon.getCapabilities()
        if (caps != null) {
            lines += "  Version: ${caps.version}"
            lines += "  Server mode: ${if (caps.serverMode) "supported (>= 1.5.1)" else "not available (< 1.5.1)"}"
        }

        // --- Daemon server ---
        lines += ""
        val running = daemon.isServerRunning()
        lines += "Daemon server: ${if (running) "running" else "stopped"}"

        if (status == DocscribeStatus.AVAILABLE) {
            val shouldRun = caps?.serverMode == true
            if (shouldRun && !running) {
                lines += "  (will start on next DocScribe action)"
            } else if (!shouldRun) {
                lines += "  (server not available — using CLI fallback)"
            }
        } else if (status == DocscribeStatus.MISSING) {
            lines += "  (gem not installed — see 'docscribe gem' section above)"
        }

        // --- Settings ---
        lines += ""
        val settings = DocscribeSettings.getInstance()
        lines += "Settings:"
        lines += "  hideCommentsByDefault = ${settings.hideCommentsByDefault}"

        // --- Diagnostics summary ---
        lines += ""
        val issues = mutableListOf<String>()
        if (rubyPath == null) issues += "No Ruby SDK configured or found on PATH."
        if (gemRoot == null) issues += "No Gemfile found in project tree."
        if (status == DocscribeStatus.MISSING) {
            issues += "docscribe gem is not installed. Add 'gem \"docscribe\"' to Gemfile and run 'bundle install'."
        }
        if (issues.isEmpty()) {
            lines += "Status: OK — all systems nominal."
        } else {
            lines += "Issues found:"
            issues.forEach { lines += "  - $it" }
        }

        return lines.joinToString("\n")
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
