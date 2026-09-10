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
        assertTrue(isRubyFile("models/user.rb"))
    }

    fun testIsRubyFileAcceptsRake() {
        assertTrue(isRubyFile("tasks/build.rake"))
    }

    fun testIsRubyFileAcceptsRakefileWithoutExtension() {
        assertTrue(isRubyFile("Rakefile"))
    }

    fun testIsRubyFileRejectsNonRubyFiles() {
        assertFalse(isRubyFile("user.txt"))
        assertFalse(isRubyFile("Rakefile.txt"))
        assertFalse(isRubyFile("Gemfile"))
    }

    // ── file collection ─────────────────────────────────────────────

    fun testCollectRubyFilesFiltersFilesOutsideContentRoots() {
        // A real directory is not part of the project content roots, so nothing is collected.
        val dir = createTempDirectory().toFile()
        val rubyFile = File(dir, "app.rb")
        rubyFile.writeText("class Foo; end")
        File(dir, "plain.txt").writeText("plain")
        File(dir, "Rakefile").writeText("task :test do\nend")

        val files = collectRubyFiles(project, dir.absolutePath)

        assertTrue(files.isEmpty())
        assertTrue(rubyFile.exists())
    }

    fun testWorkspaceChunkSizeRemainsTen() {
        assertEquals(10, WORKSPACE_CHUNK_SIZE)
    }

    fun testLoadFileFilterPatternsDefaultsToSpecExclude() {
        val dir = createTempDirectory().toFile()
        try {
            val (include, exclude) = loadFileFilterPatterns(dir.absolutePath)
            assertTrue(include.isEmpty())
            assertTrue(exclude.any { it.contains("spec") })
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testLoadFileFilterPatternsRespectsDocscribeYml() {
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "docscribe.yml").writeText(
                """
                filter:
                  files:
                    include:
                      - "app/**/*.rb"
                    exclude:
                      - "spec/**/*"
                      - "vendor/**/*"
                """.trimIndent(),
            )
            val (include, exclude) = loadFileFilterPatterns(dir.absolutePath)
            assertTrue(include.any { it.contains("app") })
            assertTrue(exclude.any { it.contains("spec") })
            assertTrue(exclude.any { it.contains("vendor") })
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testParseFilterFilesPatternsExtractsLists() {
        val yml =
            """
            filter:
              files:
                include:
                  - "lib/**/*.rb"
                exclude:
                  - "spec/**/*"
            rbs:
              enabled: true
            """.trimIndent()
        val (inc, exc) = parseFilterFilesPatterns(yml)
        assertEquals(listOf("lib/**/*.rb"), inc)
        assertEquals(listOf("spec/**/*"), exc)
    }

    fun testParseFilterFilesPatternsInlineList() {
        val yml = "filter:\n  files:\n    exclude: [\"spec\"]\n"
        val (inc, exc) = parseFilterFilesPatterns(yml)
        assertTrue(inc.isEmpty())
        assertEquals(listOf("spec"), exc)
    }

    fun testRelativePathInsideRoot() {
        val rel = relativePath("/a/b", "/a/b/c/d.rb")
        assertEquals("c/d.rb", rel)
    }

    fun testRelativePathOutsideRootFallsBack() {
        val path = "/other/c/d.rb"
        val rel = relativePath("/a/b", path)
        assertEquals(path, rel)
    }

    fun testFileMatchPatternGlob() {
        assertTrue(fileMatchPattern("app/**/*.rb", "app/models/user.rb"))
        assertFalse(fileMatchPattern("app/**/*.rb", "spec/models/user.rb"))
        assertTrue(fileMatchPattern("spec/**/*", "spec/foo/bar.rb"))
    }

    fun testFileMatchPatternRegex() {
        assertTrue(fileMatchPattern("/spec\\/.*/", "spec/foo.rb"))
        assertFalse(fileMatchPattern("/spec\\/.*/", "app/foo.rb"))
    }

    fun testFileMatchPatternDoubleStarVariant() {
        // Ruby tries both "a/**/b" and "a/b" — our impl should match both
        assertTrue(fileMatchPattern("a/**/b", "a/b"))
        assertTrue(fileMatchPattern("a/**/b", "a/x/y/b"))
    }

    fun testNormalizeFilePatternsExpandsDirectoryShorthand() {
        val dir = createTempDirectory().toFile()
        try {
            val specDir = File(dir, "spec")
            specDir.mkdir()
            val normalized = normalizeFilePatterns(listOf("spec"), dir.absolutePath)
            assertTrue(normalized.contains("spec/**/*"))
            val slashNormalized = normalizeFilePatterns(listOf("spec/"), dir.absolutePath)
            assertTrue(slashNormalized.contains("spec/**/*"))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testFileMatchesAny() {
        assertTrue(fileMatchesAny(listOf("app/**/*.rb", "lib/**/*.rb"), "app/a.rb"))
        assertFalse(fileMatchesAny(listOf("app/**/*.rb"), "spec/a.rb"))
    }

    fun testIsInContentCheckCorrect() {
        // Verify collectRubyFiles uses !isExcluded && isInContent — outside content roots should be empty
        // Also verify it doesn't crash when RbsDetector logs
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "Gemfile").writeText("source 'https://rubygems.org'\n")
            val files = collectRubyFiles(project, dir.absolutePath)
            // Temp dir not in content roots → empty regardless of RBS state
            assertTrue(files.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
