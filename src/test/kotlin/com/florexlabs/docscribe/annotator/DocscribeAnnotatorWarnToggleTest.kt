package com.florexlabs.docscribe.annotator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocscribeAnnotatorWarnToggleTest {
    @Test
    fun `warn on shows syntax invalid type`() {
        assertFalse(
            DocscribeAnnotator.isInvalidYardHidden(
                "Docscribe/InvalidType",
                "syntax",
                "invalid YARD type [Symbкol] for @param x",
                warnInvalidYardTypes = true,
            ),
        )
    }

    @Test
    fun `warn off hides syntax invalid type`() {
        assertTrue(
            DocscribeAnnotator.isInvalidYardHidden(
                "Docscribe/InvalidType",
                "syntax",
                "invalid YARD type [Symbкol] for @param x",
                warnInvalidYardTypes = false,
            ),
        )
    }

    @Test
    fun `warn off hides invalid type with null source`() {
        assertTrue(
            DocscribeAnnotator.isInvalidYardHidden(
                "Docscribe/InvalidType",
                null,
                "invalid YARD type [Array<] for @return",
                warnInvalidYardTypes = false,
            ),
        )
    }

    @Test
    fun `warn off keeps rbs sourced invalid type`() {
        assertFalse(
            DocscribeAnnotator.isInvalidYardHidden(
                "Docscribe/InvalidType",
                "rbs",
                "invalid YARD type [String] for @param x",
                warnInvalidYardTypes = false,
            ),
        )
    }

    @Test
    fun `warn off keeps other cops`() {
        assertFalse(
            DocscribeAnnotator.isInvalidYardHidden(
                "Docscribe/MissingDocBlock",
                "infer",
                "missing docs for Calc#add",
                warnInvalidYardTypes = false,
            ),
        )
        assertFalse(
            DocscribeAnnotator.isInvalidYardHidden(
                "Docscribe/UpdatedParam",
                "rbs",
                "updated @param x from String to Integer",
                warnInvalidYardTypes = false,
            ),
        )
    }
}
