package com.florexlabs.docscribe.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeAnnotatorTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/kotlin/com/florexlabs/docscribe/fixtures"

    fun testAnnotatorReturnsNullForNonRubyFile() {
        val file = myFixture.configureByText("test.txt", "some text")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNull(info)
    }

    fun testAnnotatorReturnsInfoForRubyFile() {
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull(info)
    }

    fun testAnnotatorReturnsInfoForRakeFile() {
        val file = myFixture.configureByText("Rakefile.rake", "task :test do\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull(info)
    }

    fun testNoEditorOverloadReturnsInfo() {
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull(info)
        assertEquals("test.rb", info!!.filePath.substringAfterLast("/"))
    }
}
