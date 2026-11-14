package com.florexlabs.docscribe.runner

import com.intellij.execution.process.LocalProcessService
import com.intellij.execution.process.LocalPtyOptions
import com.intellij.execution.process.RawCommandLineString
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId

/**
 * Registers a stub [LocalProcessService] for tests that spawn real processes.
 *
 * 263 moved the implementation into `intellij.platform.ide.util.io.native`, a module whose plugin descriptor is not
 * loaded into the test application, so `OSProcessHandler.hasPty()` throws "Cannot find service" once
 * `LoadingState.COMPONENTS_LOADED` is reached. In a real IDE the service always exists; only the test JVM needs it.
 *
 * A plain `CapturingProcessHandler` never starts a PTY, so the stub leaves the PTY methods unimplemented.
 */
object LocalProcessServiceStub {
    @Volatile
    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val app = ApplicationManager.getApplication()
            if (runCatching { app.service<LocalProcessService>() }.isFailure) {
                (app as ComponentManagerEx).registerServiceInstance(
                    LocalProcessService::class.java,
                    StubLocalProcessService(),
                    PluginManagerCore.getPlugin(PluginId.getId("com.florexlabs.docscribe"))!!,
                )
            }
            registered = true
        }
    }

    private class StubLocalProcessService : LocalProcessService {
        override fun startPtyProcess(
            command: List<String>,
            directory: String?,
            env: Map<String, String>,
            options: LocalPtyOptions,
            redirectErrorStream: Boolean,
        ): Process = throw UnsupportedOperationException("PTY is not used by tests")

        override fun startPtyProcess(
            command: RawCommandLineString,
            directory: String?,
            env: Map<String, String>,
            options: LocalPtyOptions,
            redirectErrorStream: Boolean,
        ): Process = throw UnsupportedOperationException("PTY is not used by tests")

        override fun sendWinProcessCtrlC(process: Process): Boolean = false

        override fun sendWinProcessCtrlC(pid: Int): Boolean = false

        override fun sendWinProcessCtrlC(
            pid: Int,
            processOutputStream: java.io.OutputStream?,
        ): Boolean = false

        override fun killWinProcessRecursively(pid: Int) = Unit

        override fun isLocalPtyProcess(process: Process): Boolean = false

        override fun hasControllingTerminal(process: Process): Boolean = false
    }
}
