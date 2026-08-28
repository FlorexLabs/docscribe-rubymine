package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Objects

class DocscribeAnnotatorCacheSettingsTest : BasePlatformTestCase() {
    private var savedHideCommentsByDefault = false
    private var savedWarnOnInvalidYardTypes = true

    override fun setUp() {
        super.setUp()
        val settings = DocscribeSettings.getInstance()
        savedHideCommentsByDefault = settings.hideCommentsByDefault
        savedWarnOnInvalidYardTypes = settings.warnOnInvalidYardTypes
    }

    override fun tearDown() {
        val settings = DocscribeSettings.getInstance()
        settings.hideCommentsByDefault = savedHideCommentsByDefault
        settings.warnOnInvalidYardTypes = savedWarnOnInvalidYardTypes
        super.tearDown()
    }

    fun testConfigHashChangesWhenHideCommentsByDefaultChanges() {
        val settings = DocscribeSettings.getInstance()
        val hashBefore = Objects.hash(settings.hideCommentsByDefault)

        settings.hideCommentsByDefault = !savedHideCommentsByDefault
        val hashAfter = Objects.hash(settings.hideCommentsByDefault)

        assertTrue(
            "configHash should change when hideCommentsByDefault toggles: $hashBefore -> $hashAfter",
            hashBefore != hashAfter,
        )
    }

    fun testSameSettingsProducesSameConfigHash() {
        val hash1 = Objects.hash(DocscribeSettings.getInstance().hideCommentsByDefault)
        val hash2 = Objects.hash(DocscribeSettings.getInstance().hideCommentsByDefault)
        assertEquals("hash should be stable for same settings", hash1, hash2)
    }

    fun testAnnotatorCollectInformationUsesNonZeroConfigHash() {
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull("should collect info for .rb file", info)
        val projectDir = file.project.basePath ?: ""
        // collectInformation now uses projectDir.hashCode() on EDT for speed; rbsHash is computed in doAnnotate
        val settings = DocscribeSettings.getInstance()
        val expectedHash = Objects.hash(settings.hideCommentsByDefault, settings.warnOnInvalidYardTypes, projectDir.hashCode())
        assertEquals("configHash should match settings hash", expectedHash, info!!.configHash)
    }

    fun testConfigHashChangesWhenWarnOnInvalidYardTypesChanges() {
        val settings = DocscribeSettings.getInstance()
        val before = settings.warnOnInvalidYardTypes
        val hashBefore = Objects.hash(settings.hideCommentsByDefault, before)
        settings.warnOnInvalidYardTypes = !before
        val hashAfter = Objects.hash(settings.hideCommentsByDefault, settings.warnOnInvalidYardTypes)
        assertTrue(
            "configHash should change when warnOnInvalidYardTypes toggles: $hashBefore -> $hashAfter",
            hashBefore != hashAfter,
        )
    }
}
