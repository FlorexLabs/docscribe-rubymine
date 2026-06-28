package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.settings.DocscribeSettings
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

    fun testConfigHashEqualsOmitBoilerplateHash() {
        val settings = DocscribeSettings.getInstance()
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertEquals(settings.omitBoilerplate.hashCode(), info!!.configHash)
    }

    fun testConfigHashChangesWithOmitBoilerplate() {
        val settings = DocscribeSettings.getInstance()
        settings.omitBoilerplate = false
        val hashFalse = DocscribeAnnotator().collectInformation(myFixture.configureByText("a.rb", "")).let {
            it!!.configHash.also { settings.omitBoilerplate = true }
        }
        settings.omitBoilerplate = true
        val hashTrue = DocscribeAnnotator().collectInformation(myFixture.configureByText("b.rb", "")).let {
            it!!.configHash.also { settings.omitBoilerplate = false }
        }
        assertNotSame(hashFalse, hashTrue)
        settings.omitBoilerplate = true
    }

    fun testNoEditorOverloadReturnsInfo() {
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull(info)
        assertEquals("test.rb", info!!.filePath.substringAfterLast("/"))
    }
}
