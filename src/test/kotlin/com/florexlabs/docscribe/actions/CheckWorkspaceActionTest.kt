package com.florexlabs.docscribe.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
