package com.florexlabs.docscribe.settings

import com.intellij.openapi.options.Configurable

class DocscribeSettingsConfigurable : Configurable {
    override fun getDisplayName(): String = "DocScribe"

    override fun createComponent(): javax.swing.JComponent? = null

    override fun isModified(): Boolean = false

    override fun apply() {}
}
