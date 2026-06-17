package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FindProjectRootTest {
    @Rule @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun `finds project root from child directory`() {
        val root = tempDir.root
        val subDir = tempDir.newFolder("a", "b", "c")
        tempDir.newFile("Gemfile")
        val found = DocscribeRunner.findProjectRoot(subDir.absolutePath)
        assertEquals(root.canonicalPath, found)
    }

    @Test
    fun `returns null when no Gemfile exists`() {
        val subDir = tempDir.newFolder("a", "b")
        assertNull(DocscribeRunner.findProjectRoot(subDir.absolutePath))
    }

    @Test
    fun `returns same directory if it contains Gemfile`() {
        val root = tempDir.root
        tempDir.newFile("Gemfile")
        assertEquals(root.canonicalPath, DocscribeRunner.findProjectRoot(root.absolutePath))
    }
}
