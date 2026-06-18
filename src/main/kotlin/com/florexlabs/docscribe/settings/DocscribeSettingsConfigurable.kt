package com.florexlabs.docscribe.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings UI page for the DocScribe plugin (under **Tools -> DocScribe**).
 *
 * Binds Swing controls to [DocscribeSettings] fields.
 */
class DocscribeSettingsConfigurable : Configurable {
    @JvmField var commandPathField = JBTextField()

    @JvmField var useBundleExecCheckBox = JBCheckBox("Use bundle exec")

    @JvmField var useRbsCheckBox = JBCheckBox("Use RBS type signatures")

    @JvmField var runOnSaveCheckBox = JBCheckBox("Run on save")

    @JvmField var omitBoilerplateCheckBox = JBCheckBox("Omit boilerplate text")

    @JvmField var hideCommentsCheckBox = JBCheckBox("Hide comments by default")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "DocScribe"

    override fun createComponent(): JComponent {
        val settings = DocscribeSettings.getInstance()
        commandPathField.text = settings.commandPath
        useBundleExecCheckBox.isSelected = settings.useBundleExec
        useRbsCheckBox.isSelected = settings.useRbs
        runOnSaveCheckBox.isSelected = settings.runOnSave
        omitBoilerplateCheckBox.isSelected = settings.omitBoilerplate
        hideCommentsCheckBox.isSelected = settings.hideCommentsByDefault
        panel =
            FormBuilder
                .createFormBuilder()
                .addLabeledComponent("Command path:", commandPathField)
                .addComponent(useBundleExecCheckBox)
                .addComponent(useRbsCheckBox)
                .addComponent(omitBoilerplateCheckBox)
                .addComponent(hideCommentsCheckBox)
                .addComponent(runOnSaveCheckBox)
                .addComponentFillVertically(JPanel(), 0)
                .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = DocscribeSettings.getInstance()
        return commandPathField.text != s.commandPath ||
            useBundleExecCheckBox.isSelected != s.useBundleExec ||
            useRbsCheckBox.isSelected != s.useRbs ||
            omitBoilerplateCheckBox.isSelected != s.omitBoilerplate ||
            hideCommentsCheckBox.isSelected != s.hideCommentsByDefault ||
            runOnSaveCheckBox.isSelected != s.runOnSave
    }

    override fun apply() {
        val s = DocscribeSettings.getInstance()
        s.commandPath = commandPathField.text
        s.useBundleExec = useBundleExecCheckBox.isSelected
        s.useRbs = useRbsCheckBox.isSelected
        s.omitBoilerplate = omitBoilerplateCheckBox.isSelected
        s.hideCommentsByDefault = hideCommentsCheckBox.isSelected
        s.runOnSave = runOnSaveCheckBox.isSelected
    }

    override fun reset() {
        val s = DocscribeSettings.getInstance()
        commandPathField.text = s.commandPath
        useBundleExecCheckBox.isSelected = s.useBundleExec
        useRbsCheckBox.isSelected = s.useRbs
        omitBoilerplateCheckBox.isSelected = s.omitBoilerplate
        hideCommentsCheckBox.isSelected = s.hideCommentsByDefault
        runOnSaveCheckBox.isSelected = s.runOnSave
    }
}
