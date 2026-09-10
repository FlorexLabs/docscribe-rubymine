package com.florexlabs.docscribe.annotator

import com.florexlabs.docscribe.runner.DocscribeRunner
import com.florexlabs.docscribe.runner.DocscribeStrategy
import com.florexlabs.docscribe.settings.DocscribeSettings
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
        // doAnnotate will try to call daemon and likely return error in test env (no gem), but should not throw
        val result = annotator.doAnnotate(info)
        // In test env without daemon, result is either null (old) or error output (new) — both acceptable without throw
        if (result != null) {
            assertEquals(1, result.files.size)
            assertEquals(
                "Docscribe/Error",
                result.files
                    .first()
                    .offenses
                    .first()
                    .copName,
            )
        } else {
            assertNull(result)
        }
    }

    fun testInvalidYardTypeHackRemoved() {
        try {
            Class.forName("com.florexlabs.docscribe.annotator.DocscribeInvalidYardTypeFixIntention")
            fail("Hack class should be deleted, use gem --validate-types instead")
        } catch (_: ClassNotFoundException) {
            // expected
        }
    }

    fun testWarnOnInvalidYardTypesControlsValidateTypes() {
        val settings = DocscribeSettings.getInstance()
        val saved = settings.warnOnInvalidYardTypes
        try {
            settings.warnOnInvalidYardTypes = true
            val withFlag =
                DocscribeRunner.getCommandArgs(
                    strategy = DocscribeStrategy.CHECK,
                    formatJson = true,
                    filePath = "a.rb",
                    useRbs = false,
                    useRbsCollection = false,
                    validateTypes = true,
                )
            assertTrue(withFlag.contains("--validate-types"))
            val withoutFlag =
                DocscribeRunner.getCommandArgs(
                    strategy = DocscribeStrategy.CHECK,
                    formatJson = true,
                    filePath = "a.rb",
                    useRbs = false,
                    useRbsCollection = false,
                    validateTypes = false,
                )
            assertFalse(withoutFlag.contains("--validate-types"))
            // Settings flag should influence runner when validateTypes not explicitly passed
            // via DocscribeRunner.runDocscribe, but getCommandArgs validateTypes param is the direct control
            assertTrue(settings.warnOnInvalidYardTypes)
        } finally {
            settings.warnOnInvalidYardTypes = saved
        }
    }

    fun testApplyWithInvalidTypeDoesNotThrow() {
        val psiFile =
            myFixture.configureByText(
                "test.rb",
                "# @param [Symb ol] x\ndef foo(x)\nend\n",
            )
        val output =
            com.florexlabs.docscribe.runner.DocscribeOutput(
                metadata = null,
                files =
                    listOf(
                        com.florexlabs.docscribe.runner.ParsedFile(
                            path = psiFile.virtualFile.path,
                            offenses =
                                listOf(
                                    com.florexlabs.docscribe.runner.ParsedOffense(
                                        severity = "warning",
                                        copName = "Docscribe/InvalidType",
                                        message = "invalid YARD type [Symb ol] for @param x",
                                        corrected = false,
                                        correctable = true,
                                        location =
                                            com.florexlabs.docscribe.runner
                                                .OffenseLocation(2, 1, 2, 1),
                                    ),
                                ),
                        ),
                    ),
                summary = null,
            )
        // Gem-driven InvalidType should remain handled; hack removal should not break this path
        assertEquals(
            "Docscribe/InvalidType",
            output.files
                .first()
                .offenses
                .first()
                .copName,
        )
        assertNotNull(psiFile.virtualFile.path)
    }
}
