package com.florexlabs.docscribe.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

class CheckWorkspaceActionTest : BasePlatformTestCase() {
    private val action = CheckWorkspaceAction()

    fun testCheckWorkspaceActionIsEnabledWithProject() {
        val dataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, myFixture.project)
                .build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertTrue(presentation.isEnabledAndVisible)
    }

    fun testCheckWorkspaceActionIsDisabledWithoutProject() {
        val dataContext =
            SimpleDataContext
                .builder()
                .build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertFalse(presentation.isEnabledAndVisible)
    }

    fun testCheckWorkspaceActionUpdateThreadIsBGT() {
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    // ── Ruby file detection ─────────────────────────────────────────

    fun testIsRubyFileAcceptsRb() {
        assertTrue(CheckWorkspaceAction.isRubyFile("models/user.rb"))
    }

    fun testIsRubyFileAcceptsRake() {
        assertTrue(CheckWorkspaceAction.isRubyFile("tasks/build.rake"))
    }

    fun testIsRubyFileAcceptsRakefileWithoutExtension() {
        assertTrue(CheckWorkspaceAction.isRubyFile("Rakefile"))
    }

    fun testIsRubyFileRejectsNonRubyFiles() {
        assertFalse(CheckWorkspaceAction.isRubyFile("user.txt"))
        assertFalse(CheckWorkspaceAction.isRubyFile("Rakefile.txt"))
        assertFalse(CheckWorkspaceAction.isRubyFile("Gemfile"))
    }

    // ── file collection ─────────────────────────────────────────────

    fun testCollectRubyFilesFiltersFilesOutsideContentRoots() {
        // A real directory is not part of the project content roots, so nothing is collected.
        val dir = createTempDirectory().toFile()
        val rubyFile = File(dir, "app.rb")
        rubyFile.writeText("class Foo; end")
        File(dir, "plain.txt").writeText("plain")
        File(dir, "Rakefile").writeText("task :test do\nend")

        val files = CheckWorkspaceAction.collectRubyFiles(project, dir.absolutePath)

        assertTrue(files.isEmpty())
        assertTrue(rubyFile.exists())
    }
}
