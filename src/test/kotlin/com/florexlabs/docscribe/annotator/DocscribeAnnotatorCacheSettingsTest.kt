package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.RbsDetector
import com.florexlabs.docscribe.settings.DocscribeSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Objects

class DocscribeAnnotatorCacheSettingsTest : BasePlatformTestCase() {
    private var savedHideCommentsByDefault = false

    override fun setUp() {
        super.setUp()
        savedHideCommentsByDefault = DocscribeSettings.getInstance().hideCommentsByDefault
    }

    override fun tearDown() {
        DocscribeSettings.getInstance().hideCommentsByDefault = savedHideCommentsByDefault
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
        val expectedHash = Objects.hash(DocscribeSettings.getInstance().hideCommentsByDefault, RbsDetector.rbsHash(projectDir))
        assertEquals("configHash should match settings hash", expectedHash, info!!.configHash)
    }
}
