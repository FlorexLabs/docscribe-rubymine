package com.florexlabs.docscribe.settings

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic

/**
 * Listener interface for DocScribe settings change events.
 *
 * Subscribe via [TOPIC] on the application message bus.
 */
interface DocscribeSettingsChangeListener {
    /**
     * Called when DocScribe settings have been saved.
     */
    fun settingsChanged()

    companion object {
        /**
         * The topic key for DocScribe settings change events.
         */
        val TOPIC: Topic<DocscribeSettingsChangeListener> =
            Topic.create("DocScribe settings changed", DocscribeSettingsChangeListener::class.java)
    }
}

/**
 * Application-level service that listens for DocScribe settings changes and refreshes code folding.
 *
 * When settings change, iterates all open projects and editors, calling
 * [CodeFoldingManager.updateFoldRegionsAsync] to rebuild folding regions.
 */
@Service(Service.Level.APP)
internal class DocscribeSettingsChangeListenerImpl : DocscribeSettingsChangeListener {
    init {
        ApplicationManager
            .getApplication()
            .messageBus
            .connect()
            .subscribe(DocscribeSettingsChangeListener.TOPIC, this)
    }

    /**
     * Refresh code folding in all open editors when settings change.
     *
     * Iterates all open projects and their text editors, triggering an async folding region update.
     */
    override fun settingsChanged() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val fileEditorManager = FileEditorManager.getInstance(project)
            for (editor in fileEditorManager.allEditors) {
                val textEditor = editor as? TextEditor ?: continue
                CodeFoldingManager.getInstance(project).updateFoldRegionsAsync(textEditor.editor, true)
            }
        }
    }
}
