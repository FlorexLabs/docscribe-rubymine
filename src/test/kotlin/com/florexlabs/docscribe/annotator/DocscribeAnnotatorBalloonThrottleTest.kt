package com.florexlabs.docscribe.annotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocscribeAnnotatorBalloonThrottleTest {
    @Test
    fun `throttle interval is 15 minutes`() {
        assertEquals(15 * 60 * 1000L, DocscribeAnnotator.BALLOON_THROTTLE_MS)
    }

    @Test
    fun `first show is allowed`() {
        assertTrue(DocscribeAnnotator.shouldShowGemBalloon(null, 1_000L))
    }

    @Test
    fun `repeat within window is suppressed`() {
        val now = 1_000_000L
        assertFalse(DocscribeAnnotator.shouldShowGemBalloon(now, now))
        assertFalse(DocscribeAnnotator.shouldShowGemBalloon(now - 1_000L, now))
        assertFalse(DocscribeAnnotator.shouldShowGemBalloon(now - DocscribeAnnotator.BALLOON_THROTTLE_MS + 1, now))
    }

    @Test
    fun `show allowed exactly at boundary`() {
        val now = 1_000_000L
        assertTrue(DocscribeAnnotator.shouldShowGemBalloon(now - DocscribeAnnotator.BALLOON_THROTTLE_MS, now))
    }

    @Test
    fun `show allowed after window elapsed`() {
        val now = 1_000_000L
        assertTrue(DocscribeAnnotator.shouldShowGemBalloon(now - DocscribeAnnotator.BALLOON_THROTTLE_MS - 1, now))
        assertTrue(DocscribeAnnotator.shouldShowGemBalloon(0L, now))
    }
}
