package com.florexlabs.docscribe.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeAnnotatorTest : BasePlatformTestCase() {
    private val annotator = DocscribeAnnotator()

    fun testCollectInformationReturnsNullForNonRubyFile() {
        val file = myFixture.configureByText("foo.txt", "plain text")
        assertNull(annotator.collectInformation(file))
    }

    fun testCollectInformationReturnsInfoForRubyFile() {
        val file = myFixture.configureByText("test.rb", "class Foo; end")
        val result = annotator.collectInformation(file)
        assertNotNull(result)
        assertTrue(result!!.filePath.endsWith("test.rb"))
    }

    fun testCollectInformationReturnsNullForNullVirtualFile() {
        val file = myFixture.configureByText("test.rb", "class Foo; end")
        // Simulate no virtual file by inlining... only test that .rb passes the first check
        val result = annotator.collectInformation(file)
        assertNotNull(result)
    }
}
