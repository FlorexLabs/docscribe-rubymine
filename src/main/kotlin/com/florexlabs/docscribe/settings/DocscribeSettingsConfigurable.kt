package com.florexlabs.docscribe.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings UI page for the DocScribe plugin (under **Tools -> DocScribe**).
 *
 * Binds Swing controls to [DocscribeSettings] fields.
 */
class DocscribeSettingsConfigurable : Configurable {
    @JvmField var hideCommentsCheckBox = JBCheckBox("Hide comments by default")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "DocScribe"

    override fun createComponent(): JComponent {
        val settings = DocscribeSettings.getInstance()
        hideCommentsCheckBox.isSelected = settings.hideCommentsByDefault
        panel =
            FormBuilder
                .createFormBuilder()
                .addComponent(hideCommentsCheckBox)
                .addComponentFillVertically(JPanel(), 0)
                .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = DocscribeSettings.getInstance()
        return hideCommentsCheckBox.isSelected != s.hideCommentsByDefault
    }

    override fun apply() {
        val s = DocscribeSettings.getInstance()
        s.hideCommentsByDefault = hideCommentsCheckBox.isSelected
        ApplicationManager
            .getApplication()
            .messageBus
            .syncPublisher(DocscribeSettingsChangeListener.TOPIC)
            .settingsChanged()
    }

    override fun reset() {
        val s = DocscribeSettings.getInstance()
        hideCommentsCheckBox.isSelected = s.hideCommentsByDefault
    }
}
