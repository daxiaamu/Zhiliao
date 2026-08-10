package com.shatyuka.zhiliao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

internal enum class MarkdownBlockKind {
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLET,
    NUMBERED,
    QUOTE,
    CODE,
    PARAGRAPH,
    DIVIDER,
    GAP,
}

internal data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val text: String = "",
    val marker: String = "",
)

private val headingPattern = Regex("""^(#{1,6})\s+(.+)$""")
private val bulletPattern = Regex("""^\s*[-+*]\s+(.+)$""")
private val numberedPattern = Regex("""^\s*(\d+[.)])\s+(.+)$""")
private val quotePattern = Regex("""^\s*>\s?(.*)$""")
private val dividerPattern = Regex("""^\s*((\*\s*){3,}|(-\s*){3,}|(_\s*){3,})$""")
private val inlinePattern = Regex(
    """\[([^]]+)]\((https?://[^)\s]+)\)|\*\*([^*\n]+)\*\*|__([^_\n]+)__|`([^`\n]+)`|\*([^*\n]+)\*|_([^_\n]+)_""",
)

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val code = mutableListOf<String>()
    var inCodeFence = false

    markdown.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCodeFence) {
                blocks += MarkdownBlock(MarkdownBlockKind.CODE, code.joinToString("\n"))
                code.clear()
            }
            inCodeFence = !inCodeFence
            return@forEach
        }
        if (inCodeFence) {
            code += line
            return@forEach
        }

        val heading = headingPattern.matchEntire(line)
        val bullet = bulletPattern.matchEntire(line)
        val numbered = numberedPattern.matchEntire(line)
        val quote = quotePattern.matchEntire(line)
        blocks += when {
            line.isBlank() -> MarkdownBlock(MarkdownBlockKind.GAP)
            dividerPattern.matches(line) -> MarkdownBlock(MarkdownBlockKind.DIVIDER)
            heading != null -> MarkdownBlock(
                when (heading.groupValues[1].length) {
                    1 -> MarkdownBlockKind.HEADING_1
                    2 -> MarkdownBlockKind.HEADING_2
                    else -> MarkdownBlockKind.HEADING_3
                },
                heading.groupValues[2],
            )
            bullet != null -> MarkdownBlock(MarkdownBlockKind.BULLET, bullet.groupValues[1], "•")
            numbered != null -> MarkdownBlock(
                MarkdownBlockKind.NUMBERED,
                numbered.groupValues[2],
                numbered.groupValues[1],
            )
            quote != null -> MarkdownBlock(MarkdownBlockKind.QUOTE, quote.groupValues[1])
            else -> MarkdownBlock(MarkdownBlockKind.PARAGRAPH, line)
        }
    }
    if (code.isNotEmpty()) {
        blocks += MarkdownBlock(MarkdownBlockKind.CODE, code.joinToString("\n"))
    }
    return blocks.dropWhile { it.kind == MarkdownBlockKind.GAP }
        .dropLastWhile { it.kind == MarkdownBlockKind.GAP }
}

private fun inlineMarkdown(
    text: String,
    linkColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    inlinePattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val start = length
        when {
            match.groupValues[1].isNotEmpty() -> {
                append(match.groupValues[1])
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    start,
                    length,
                )
                addLink(LinkAnnotation.Url(match.groupValues[2]), start, length)
            }
            match.groupValues[3].isNotEmpty() -> {
                append(match.groupValues[3])
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            match.groupValues[4].isNotEmpty() -> {
                append(match.groupValues[4])
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            match.groupValues[5].isNotEmpty() -> {
                append(match.groupValues[5])
                addStyle(
                    SpanStyle(background = codeBackground, fontFamily = FontFamily.Monospace),
                    start,
                    length,
                )
            }
            match.groupValues[6].isNotEmpty() -> {
                append(match.groupValues[6])
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
            else -> {
                append(match.groupValues[7])
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
fun SimpleMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val blocks = parseMarkdownBlocks(markdown)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val inline = { text: String -> inlineMarkdown(text, linkColor, codeBackground) }
            when (block.kind) {
                MarkdownBlockKind.HEADING_1 -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                MarkdownBlockKind.HEADING_2 -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MarkdownBlockKind.HEADING_3 -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                MarkdownBlockKind.BULLET,
                MarkdownBlockKind.NUMBERED,
                -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker)
                    Text(inline(block.text), modifier = Modifier.weight(1f))
                }
                MarkdownBlockKind.QUOTE -> Text(
                    inline("│ ${block.text}"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
                MarkdownBlockKind.CODE -> Text(
                    block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(codeBackground)
                        .padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                MarkdownBlockKind.PARAGRAPH -> Text(inline(block.text))
                MarkdownBlockKind.DIVIDER -> HorizontalDivider()
                MarkdownBlockKind.GAP -> if (
                    index > 0 && blocks[index - 1].kind != MarkdownBlockKind.GAP
                ) {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
