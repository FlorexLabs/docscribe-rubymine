package com.florexlabs.docscribe.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UpdateTypesActionTest : BasePlatformTestCase() {
    private val action = UpdateTypesAction()

    fun testUpdateTypesActionIsDisabledWithoutProject() {
        val dataContext = SimpleDataContext.builder().build()
        val presentation = Presentation()
        val event = AnActionEvent.createEvent(dataContext, presentation, "test", ActionUiKind.NONE, null)
        action.update(event)
        assertFalse(presentation.isEnabledAndVisible)
    }

    fun testUpdateTypesActionUpdateThreadIsBGT() {
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }
}
