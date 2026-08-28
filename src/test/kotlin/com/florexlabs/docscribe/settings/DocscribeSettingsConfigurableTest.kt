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

    fun testIsModifiedAfterHideCommentsToggle() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.hideCommentsCheckBox.isSelected = !c.hideCommentsCheckBox.isSelected
        assertTrue(c.isModified)
    }

    fun testIsModifiedAfterWarnInvalidToggle() {
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.warnInvalidYardCheckBox.isSelected = !c.warnInvalidYardCheckBox.isSelected
        assertTrue(c.isModified)
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

    fun testApplyPersistsWarnInvalid() {
        val s = DocscribeSettings.getInstance()
        val original = s.warnOnInvalidYardTypes
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.warnInvalidYardCheckBox.isSelected = !original
        c.apply()
        assertEquals(!original, s.warnOnInvalidYardTypes)
        s.warnOnInvalidYardTypes = original
        c.reset()
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

    fun testResetRestoresWarnInvalid() {
        val s = DocscribeSettings.getInstance()
        s.warnOnInvalidYardTypes = false
        val c = DocscribeSettingsConfigurable()
        c.createComponent()
        c.warnInvalidYardCheckBox.isSelected = true
        c.reset()
        assertFalse(c.warnInvalidYardCheckBox.isSelected)
        s.warnOnInvalidYardTypes = true
    }
}
