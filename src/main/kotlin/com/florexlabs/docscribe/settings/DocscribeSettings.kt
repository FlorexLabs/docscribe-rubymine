package com.florexlabs.docscribe.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "DocscribeSettings", storages = [Storage("docscribe-settings.xml")])
class DocscribeSettings : PersistentStateComponent<DocscribeSettings> {
    var commandPath: String = "docscribe"
    var useBundleExec: Boolean = false
    var runOnSave: Boolean = true
    var useRbs: Boolean = false

    override fun getState(): DocscribeSettings = this

    override fun loadState(state: DocscribeSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(): DocscribeSettings =
            ApplicationManager.getApplication().getService(DocscribeSettings::class.java)
    }
}
