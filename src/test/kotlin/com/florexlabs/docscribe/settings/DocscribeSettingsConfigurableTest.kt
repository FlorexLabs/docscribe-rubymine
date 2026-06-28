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

    fun testIsModifiedAfterOmitBoilerplateToggle() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.omitBoilerplateCheckBox.isSelected = !c.omitBoilerplateCheckBox.isSelected
        assertTrue(c.isModified)
    }

    fun testIsModifiedAfterHideCommentsToggle() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.hideCommentsCheckBox.isSelected = !c.hideCommentsCheckBox.isSelected
        assertTrue(c.isModified)
    }

    fun testIsModifiedFalseAfterApply() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.omitBoilerplateCheckBox.isSelected = !c.omitBoilerplateCheckBox.isSelected
        c.apply()
        assertFalse(c.isModified)
    }

    fun testApplyPersistsOmitBoilerplate() {
        val s = DocscribeSettings.getInstance()
        val originalOmit = s.omitBoilerplate
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.omitBoilerplateCheckBox.isSelected = false
        c.apply()
        assertFalse(s.omitBoilerplate)
        s.omitBoilerplate = originalOmit
    }

    fun testApplyPersistsHideComments() {
        val s = DocscribeSettings.getInstance()
        val originalHide = s.hideCommentsByDefault
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.hideCommentsCheckBox.isSelected = true
        c.apply()
        assertTrue(s.hideCommentsByDefault)
        s.hideCommentsByDefault = originalHide
    }

    fun testResetRestoresOmitBoilerplate() {
        val s = DocscribeSettings.getInstance()
        s.omitBoilerplate = false
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.omitBoilerplateCheckBox.isSelected = true
        c.reset()
        assertFalse(c.omitBoilerplateCheckBox.isSelected)
        s.omitBoilerplate = true
    }

    fun testResetRestoresHideComments() {
        val s = DocscribeSettings.getInstance()
        s.hideCommentsByDefault = true
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.hideCommentsCheckBox.isSelected = false
        c.reset()
        assertTrue(c.hideCommentsCheckBox.isSelected)
        s.hideCommentsByDefault = false
    }
}
