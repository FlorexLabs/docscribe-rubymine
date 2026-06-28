package com.florexlabs.docscribe.folding

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class YardFoldingBuilderTest : BasePlatformTestCase() {
    private val builder = YardFoldingBuilder()

    fun testBuildFoldRegionsReturnsEmptyForNonRubyFile() {
        myFixture.configureByText("test.txt", "some text")
        val regions = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
        assertEmpty(regions)
    }

    fun testBuildFoldRegionsReturnsEmptyForRubyFileWithoutComments() {
        myFixture.configureByText("test.rb", "class Foo\nend")
        val regions = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
        assertEmpty(regions)
    }

    fun testBuildFoldRegionsReturnsSingleRegionForYardBlock() {
        myFixture.configureByText("test.rb", "# @param name [String]\n# @return [void]\ndef foo\nend\n")
        val regions = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].range.startOffset)
        assertEquals(39, regions[0].range.endOffset)
    }

    fun testBuildFoldRegionsReturnsEmptyForCommentBlockWithoutYardTags() {
        myFixture.configureByText("test.rb", "# just a comment\n# another comment\ndef foo\nend\n")
        val regions = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
        assertEmpty(regions)
    }

    fun testBuildFoldRegionsMergesAdjacentYardBlocks() {
        myFixture.configureByText(
            "test.rb",
            "# @param name [String]\n# @return [void]\n" +
                "class Foo\n  # @return [String]\n  # @param value [Integer]\n  def bar\n  end\nend\n",
        )
        val regions = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
        assertEquals(2, regions.size)
    }

    fun testGetPlaceholderText() {
        myFixture.configureByText("test.rb", "class Foo\nend")
        assertEquals(" // ...", builder.getPlaceholderText(myFixture.file.node))
    }

    fun testIsCollapsedByDefaultReturnsSettingsValue() {
        myFixture.configureByText("test.rb", "class Foo\nend")
        assertFalse(builder.isCollapsedByDefault(myFixture.file.node))
    }
}
