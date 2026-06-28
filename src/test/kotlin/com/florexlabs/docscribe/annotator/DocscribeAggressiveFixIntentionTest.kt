package com.florexlabs.docscribe.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeAggressiveFixIntentionTest : BasePlatformTestCase() {
    private val intention = DocscribeAggressiveFixIntention()

    fun testAggressiveFixIntentionIsAvailableForRubyFile() {
        myFixture.configureByText("test.rb", "class Foo; end")
        assertTrue(intention.isAvailable(myFixture.project, null, myFixture.file))
    }

    fun testAggressiveFixIntentionIsNotAvailableForNonRubyFile() {
        myFixture.configureByText("foo.txt", "text")
        assertFalse(intention.isAvailable(myFixture.project, null, myFixture.file))
    }

    fun testAggressiveFixIntentionTextIsCorrect() {
        assertEquals("DocScribe: Apply aggressive fix", intention.text)
    }

    fun testAggressiveFixIntentionFamilyNameIsCorrect() {
        assertEquals("DocScribe", intention.familyName)
    }

    fun testAggressiveFixIntentionDoesNotStartInWriteAction() {
        assertFalse(intention.startInWriteAction())
    }
}
