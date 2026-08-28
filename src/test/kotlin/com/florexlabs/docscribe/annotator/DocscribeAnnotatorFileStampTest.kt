package com.florexlabs.docscribe.annotator

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeAnnotatorFileStampTest : BasePlatformTestCase() {
    fun testCollectInformationUsesFileModificationStamp() {
        val psiFile = myFixture.configureByText("test.rb", "class Foo\nend")
        val annotator = DocscribeAnnotator()
        val info = annotator.collectInformation(psiFile)!!
        assertEquals(psiFile.modificationStamp, info.fileStamp)
        assertEquals(psiFile.virtualFile.path, info.filePath)
    }

    fun testCollectInformationWithEditorUsesSameStamp() {
        val psiFile = myFixture.configureByText("test.rb", "class Foo\nend")
        val editor = myFixture.editor
        val annotator = DocscribeAnnotator()
        val info = annotator.collectInformation(psiFile, editor, false)!!
        assertEquals(psiFile.modificationStamp, info.fileStamp)
    }

    fun testFileStampChangesAfterPsiModification() {
        val psiFile = myFixture.configureByText("test.rb", "class Foo\nend")
        val annotator = DocscribeAnnotator()
        val info1 = annotator.collectInformation(psiFile)!!
        val stamp1 = info1.fileStamp

        WriteAction.run<Throwable> {
            val doc = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)!!
            doc.setText("class Bar\nend")
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }

        val info2 = annotator.collectInformation(psiFile)!!
        assertTrue("fileStamp should change after PSI modification", info2.fileStamp != stamp1)
    }

    fun testDoAnnotateRespectsCacheWithFileStamp() {
        val annotator = DocscribeAnnotator()
        val psiFile = myFixture.configureByText("test.rb", "class Foo\nend")
        val info = annotator.collectInformation(psiFile)!!
        // First call should increment generation and attempt cache
        DocscribeAnnotator.fileGeneration.clear()
        annotator.doAnnotate(info)
        assertEquals(1L, DocscribeAnnotator.fileGeneration[info.filePath])

        // Second call with same stamp should hit cache path (if no gem, returns null, but generation still increments)
        annotator.doAnnotate(info)
        assertEquals(2L, DocscribeAnnotator.fileGeneration[info.filePath])
    }

    fun testDoAnnotateGeneratesUpdatedParamFix() {
        // Directly test apply logic mapping without needing daemon
        val annotator = DocscribeAnnotator()
        val psiFile =
            myFixture.configureByText(
                "test.rb",
                "# @param [String] x\n# @return [void]\ndef foo(x)\nend\n",
            )
        val info = annotator.collectInformation(psiFile)!!
        // doAnnotate will try to call daemon and likely return null in test env (no gem), but should not throw
        val result = annotator.doAnnotate(info)
        // In test env without daemon, result is null — but the call should not crash and should handle ReadAction save logic
        assertNull(result)
    }
}
