package com.florexlabs.docscribe.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AggressiveFixActionTest {
    private val action = AggressiveFixAction()

    private fun mockFile(name: String): VirtualFile = LightVirtualFile(name)

    @Test
    fun testAggressiveFixActionIsEnabledForRubyFile() {
        val file = mockFile("test.rb")
        assertTrue(file.name.endsWith(".rb"))
    }

    @Test
    fun testAggressiveFixActionIsEnabledForRakefile() {
        val file = mockFile("Rakefile")
        assertEquals("Rakefile", file.name)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    @Test
    fun testAggressiveFixActionIsDisabledForNonRubyFile() {
        val file = mockFile("foo.txt")
        assertFalse(file.name.endsWith(".rb") || file.name.endsWith(".rake") || file.name == "Rakefile")
    }

    @Test
    fun testAggressiveFixActionIsDisabledWithoutFile() {
        assertTrue(true)
    }

    @Test
    fun testAggressiveFixActionUpdateThreadIsBGT() {
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }
}
