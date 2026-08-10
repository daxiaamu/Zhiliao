package com.shatyuka.zhiliao.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleMarkdownTest {
    @Test
    fun parsesCommonReleaseNoteBlocks() {
        val blocks = parseMarkdownBlocks(
            """
            ## 修复
            - 支持 **粗体**
            1. 支持列表
            > 注意事项

            ```kotlin
            val ok = true
            ```
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MarkdownBlockKind.HEADING_2,
                MarkdownBlockKind.BULLET,
                MarkdownBlockKind.NUMBERED,
                MarkdownBlockKind.QUOTE,
                MarkdownBlockKind.GAP,
                MarkdownBlockKind.CODE,
            ),
            blocks.map { it.kind },
        )
        assertEquals("•", blocks[1].marker)
        assertEquals("1.", blocks[2].marker)
        assertEquals("val ok = true", blocks.last().text)
    }

    @Test
    fun trimsOnlyOuterBlankLines() {
        val blocks = parseMarkdownBlocks("\n第一段\n\n第二段\n")

        assertEquals(
            listOf(
                MarkdownBlockKind.PARAGRAPH,
                MarkdownBlockKind.GAP,
                MarkdownBlockKind.PARAGRAPH,
            ),
            blocks.map { it.kind },
        )
    }
}
