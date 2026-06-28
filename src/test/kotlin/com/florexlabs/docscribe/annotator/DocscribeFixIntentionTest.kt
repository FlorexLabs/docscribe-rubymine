package com.florexlabs.docscribe.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeFixIntentionTest : BasePlatformTestCase() {
    private val intention = DocscribeFixIntention()

    fun testFixIntentionIsAvailableForRubyFile() {
        myFixture.configureByText("test.rb", "class Foo; end")
        assertTrue(intention.isAvailable(myFixture.project, null, myFixture.file))
    }

    fun testFixIntentionIsNotAvailableForNonRubyFile() {
        myFixture.configureByText("foo.txt", "text")
        assertFalse(intention.isAvailable(myFixture.project, null, myFixture.file))
    }

    fun testFixIntentionTextIsCorrect() {
        assertEquals("DocScribe: Apply safe fix", intention.text)
    }

    fun testFixIntentionFamilyNameIsCorrect() {
        assertEquals("DocScribe", intention.familyName)
    }

    fun testFixIntentionDoesNotStartInWriteAction() {
        assertFalse(intention.startInWriteAction())
    }
}
