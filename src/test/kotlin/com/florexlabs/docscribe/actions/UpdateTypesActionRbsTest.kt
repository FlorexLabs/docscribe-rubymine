package com.florexlabs.docscribe.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class UpdateTypesActionRbsTest : BasePlatformTestCase() {
    private val action = UpdateTypesAction()

    fun testUpdateTypesVisibleWhenSigExistsEvenWithoutGemfileRbs() {
        // Create temp project root with sig/ but no rbs in Gemfile — should be visible via RbsDetector
        val tempRoot = Files.createTempDirectory("rbs-update-rbs-test").toFile()
        try {
            val gemfile = File(tempRoot, "Gemfile")
            gemfile.writeText("source 'https://rubygems.org'\ngem 'docscribe'\n")
            val sig = File(tempRoot, "sig")
            sig.mkdir()
            File(sig, "foo.rbs").writeText("class Foo; end")

            // Mock project basePath to tempRoot via presentation check
            // We test RbsDetector directly — action.update delegates to it
            assertTrue(
                com.florexlabs.docscribe.runner.RbsDetector
                    .shouldUseRbs(tempRoot.absolutePath),
            )
            // The action's visibility is derived from RbsDetector, so if shouldUseRbs true, it would be visible
            // We verify the detector logic rather than the full AnAction update (which needs project.basePath)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun testUpdateTypesHiddenWhenNoRbsAtAll() {
        val tempRoot = Files.createTempDirectory("rbs-update-no-rbs").toFile()
        try {
            File(tempRoot, "Gemfile").writeText("source 'https://rubygems.org'\n")
            assertFalse(
                com.florexlabs.docscribe.runner.RbsDetector
                    .shouldUseRbs(tempRoot.absolutePath),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun testUpdateTypesHiddenWhenExplicitDisabledInYml() {
        val tempRoot = Files.createTempDirectory("rbs-update-yml").toFile()
        try {
            val sig = File(tempRoot, "sig")
            sig.mkdir()
            File(sig, "foo.rbs").writeText("class Foo; end")
            File(tempRoot, "docscribe.yml").writeText("rbs:\n  enabled: false\n")
            assertFalse(
                com.florexlabs.docscribe.runner.RbsDetector
                    .shouldUseRbs(tempRoot.absolutePath),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun testUpdateTypesVisibleWhenGemfileHasRbs() {
        val tempRoot = Files.createTempDirectory("rbs-update-gemfile").toFile()
        try {
            File(tempRoot, "Gemfile").writeText("source 'https://rubygems.org'\ngem 'rbs'\n")
            assertTrue(
                com.florexlabs.docscribe.runner.RbsDetector
                    .shouldUseRbs(tempRoot.absolutePath),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun testUpdateTypesActionUpdateThreadIsBGT() {
        assertEquals(com.intellij.openapi.actionSystem.ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun testUpdateTypesActionDisabledWithoutProjectStillHolds() {
        val dataContext = SimpleDataContext.builder().build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertFalse(presentation.isEnabledAndVisible)
    }
}
