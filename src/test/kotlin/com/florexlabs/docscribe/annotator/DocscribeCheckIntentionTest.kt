package com.florexlabs.docscribe.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeCheckIntentionTest : BasePlatformTestCase() {
    private val intention = DocscribeCheckIntention()

    fun testCheckIntentionIsAvailableForRubyFile() {
        myFixture.configureByText("test.rb", "class Foo; end")
        assertTrue(intention.isAvailable(myFixture.project, null, myFixture.file))
    }

    fun testCheckIntentionIsNotAvailableForNonRubyFile() {
        myFixture.configureByText("foo.txt", "text")
        assertFalse(intention.isAvailable(myFixture.project, null, myFixture.file))
    }

    fun testCheckIntentionTextIsCorrect() {
        assertEquals("DocScribe: Check current file", intention.text)
    }

    fun testCheckIntentionFamilyNameIsCorrect() {
        assertEquals("DocScribe", intention.familyName)
    }

    fun testCheckIntentionDoesNotStartInWriteAction() {
        assertFalse(intention.startInWriteAction())
    }
}
