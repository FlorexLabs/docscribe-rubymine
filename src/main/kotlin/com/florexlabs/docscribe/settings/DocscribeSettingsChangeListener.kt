package com.florexlabs.docscribe.settings

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic

interface DocscribeSettingsChangeListener {
    fun settingsChanged()

    companion object {
        val TOPIC: Topic<DocscribeSettingsChangeListener> =
            Topic.create("DocScribe settings changed", DocscribeSettingsChangeListener::class.java)
    }
}

@Service(Service.Level.APP)
internal class DocscribeSettingsChangeListenerImpl : DocscribeSettingsChangeListener {
    init {
        ApplicationManager
            .getApplication()
            .messageBus
            .connect()
            .subscribe(DocscribeSettingsChangeListener.TOPIC, this)
    }

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
