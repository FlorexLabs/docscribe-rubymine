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

    fun testAnnotatorReturnsInfoForPlainRakefile() {
        val file = myFixture.configureByText("Rakefile", "task :test do\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull(info)
    }

    fun testNoEditorOverloadReturnsInfo() {
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = DocscribeAnnotator().collectInformation(file)
        assertNotNull(info)
        assertEquals("test.rb", info!!.filePath.substringAfterLast("/"))
    }

    fun testDoAnnotateReturnsNullWhenDocscribeNotAvailable() {
        val annotator = DocscribeAnnotator()
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = annotator.collectInformation(file)!!
        // No docscribe gem in test env — should return null without throwing
        val result = annotator.doAnnotate(info)
        assertNull(result)
    }

    fun testFileGenerationIncrementsOnNewAnnotation() {
        DocscribeAnnotator.fileGeneration.clear()
        val annotator = DocscribeAnnotator()
        val file = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = annotator.collectInformation(file)!!
        val filePath = info.filePath

        assertEquals(0L, DocscribeAnnotator.fileGeneration.getOrDefault(filePath, 0L))
        annotator.doAnnotate(info)
        assertEquals(1L, DocscribeAnnotator.fileGeneration.getOrDefault(filePath, 0L))
        annotator.doAnnotate(info)
        assertEquals(2L, DocscribeAnnotator.fileGeneration.getOrDefault(filePath, 0L))
    }

    fun testFileGenerationSeparatePerFile() {
        DocscribeAnnotator.fileGeneration.clear()
        val annotator = DocscribeAnnotator()
        val file1 = myFixture.configureByText("foo.rb", "class Foo\nend")
        val file2 = myFixture.configureByText("bar.rb", "class Bar\nend")
        val info1 = annotator.collectInformation(file1)!!
        val info2 = annotator.collectInformation(file2)!!

        annotator.doAnnotate(info1)
        annotator.doAnnotate(info2)
        annotator.doAnnotate(info1)

        assertEquals(2L, DocscribeAnnotator.fileGeneration.getOrDefault(info1.filePath, 0L))
        assertEquals(1L, DocscribeAnnotator.fileGeneration.getOrDefault(info2.filePath, 0L))
    }
}
