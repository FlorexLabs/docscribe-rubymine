package com.florexlabs.docscribe.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CheckFileActionTest : BasePlatformTestCase() {

    private val action = CheckFileAction()

    fun testActionIsEnabledForRubyFile() {
        val file = myFixture.configureByText("test.rb", "class Foo; end").virtualFile
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.PROJECT, myFixture.project)
            .build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertTrue(presentation.isEnabledAndVisible)
    }

    fun testActionIsDisabledForNonRubyFile() {
        val file = myFixture.configureByText("foo.txt", "plain text").virtualFile
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.PROJECT, myFixture.project)
            .build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertFalse(presentation.isEnabledAndVisible)
    }

    fun testActionIsDisabledWithoutFile() {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, myFixture.project)
            .build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertFalse(presentation.isEnabledAndVisible)
    }

    fun testActionUpdateThreadIsBGT() {
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }
}
