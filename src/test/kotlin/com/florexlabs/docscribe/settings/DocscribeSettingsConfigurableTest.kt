package com.florexlabs.docscribe.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeSettingsConfigurableTest : BasePlatformTestCase() {

    fun testDisplayName() {
        val c = DocscribeSettingsConfigurable()
        assertEquals("DocScribe", c.displayName)
    }

    fun testCreateComponent() {
        val c = DocscribeSettingsConfigurable()
        assertNotNull(c.createComponent())
    }

    fun testDefaultsNotModified() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        assertFalse(c.isModified)
    }

    fun testIsModifiedAfterFieldChange() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.commandPathField.text = "/custom/path"
        assertTrue(c.isModified)
    }

    fun testIsModifiedFalseAfterApply() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.commandPathField.text = "/custom/path"
        c.apply()
        assertFalse(c.isModified)
    }

    fun testApplyPersistsChanges() {
        val s = DocscribeSettings.getInstance()
        val originalPath = s.commandPath
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.commandPathField.text = "/custom/docscribe"
        c.useBundleExecCheckBox.isSelected = true
        c.omitBoilerplateCheckBox.isSelected = false
        c.apply()
        assertEquals("/custom/docscribe", s.commandPath)
        assertTrue(s.useBundleExec)
        assertFalse(s.omitBoilerplate)
        s.commandPath = originalPath
        s.useBundleExec = false
        s.omitBoilerplate = true
    }

    fun testResetRestoresFromSettings() {
        val s = DocscribeSettings.getInstance()
        s.commandPath = "/tmp/docscribe"
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.commandPathField.text = ""
        c.reset()
        assertEquals("/tmp/docscribe", c.commandPathField.text)
        s.commandPath = "docscribe"
    }
}
