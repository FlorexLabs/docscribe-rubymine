@file:Suppress("MaxLineLength")

package com.florexlabs.docscribe.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeInvalidYardTypeFixIntentionTest : BasePlatformTestCase() {
    fun testFixWithExpectedShowsArrow() {
        val intention =
            DocscribeInvalidYardTypeFixIntention(
                "invalid YARD type [Symb ol] for @return, expected [Symbol]",
                5,
            )
        assertEquals("Fix YARD type → [Symbol]", intention.text)
    }

    fun testFixWithoutExpectedShowsGeneric() {
        val intention = DocscribeInvalidYardTypeFixIntention("invalid YARD type [Arraаy<Object>] for @param args", 3)
        // Arraаy -> Array via suggestFix, so arrow should appear
        assertTrue(intention.text.contains("Fix YARD type"))
        assertTrue(intention.text.contains("Array"))
    }

    fun testFixGenericWithoutExpectedStillSuggests() {
        val intention = DocscribeInvalidYardTypeFixIntention("invalid YARD type [Objec3t] for @return", 2)
        assertTrue(intention.text.contains("Object"))
    }

    fun testIsAvailableForRubyFile() {
        val intention = DocscribeInvalidYardTypeFixIntention("invalid YARD type [Symb ol] for @return, expected [Symbol]", 0)
        myFixture.configureByText("test.rb", "class Foo; end")
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("DocScribe", intention.familyName)
        assertFalse(intention.startInWriteAction())
    }

    fun testExtractInvalidType() {
        assertEquals(
            "Symb ol",
            DocscribeInvalidYardTypeFixIntention.extractInvalidType("invalid YARD type [Symb ol] for @return, expected [Symbol]"),
        )
        assertEquals(
            "Arraаy<Object>",
            DocscribeInvalidYardTypeFixIntention.extractInvalidType("invalid YARD type [Arraаy<Object>] for @param args"),
        )
    }

    fun testExtractExpectedType() {
        assertEquals(
            "Symbol",
            DocscribeInvalidYardTypeFixIntention.extractExpectedType("invalid YARD type [Symb ol] for @return, expected [Symbol]"),
        )
        assertEquals(null, DocscribeInvalidYardTypeFixIntention.extractExpectedType("invalid YARD type [Arraаy<Object>] for @param args"))
    }

    fun testSuggestFixRemovesCyrillicAndDigit() {
        // Directly test companion suggestFix via getText
        val intention = DocscribeInvalidYardTypeFixIntention("invalid YARD type [Arraаy<Object>] for @param args", 0)
        // Arraаy (with Cyrillic) -> Array
        assertTrue(intention.text.contains("Array"))
        val intention2 = DocscribeInvalidYardTypeFixIntention("invalid YARD type [Symbo2l] for @return", 0)
        assertTrue(intention2.text.contains("Symbol"))
    }
}
