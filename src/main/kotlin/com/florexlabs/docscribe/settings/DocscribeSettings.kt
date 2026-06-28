package com.florexlabs.docscribe.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent application-level settings for the DocScribe plugin.
 *
 * Stored in `docscribe-settings.xml` under the IDE's config directory.
 */
@State(name = "DocscribeSettings", storages = [Storage("docscribe-settings.xml")])
@Service
class DocscribeSettings : PersistentStateComponent<DocscribeSettings> {
    /**
     * Whether YARD comment blocks should be folded (collapsed) by default in the editor.
     */
    var hideCommentsByDefault: Boolean = false

    /**
     * Return this instance as the state to persist.
     */
    override fun getState(): DocscribeSettings = this

    /**
     * Restore persisted settings into this instance.
     */
    override fun loadState(state: DocscribeSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        /**
         * Get the application-level [DocscribeSettings] singleton.
         */
        @JvmStatic
        fun getInstance(): DocscribeSettings = ApplicationManager.getApplication().getService(DocscribeSettings::class.java)
    }
}
