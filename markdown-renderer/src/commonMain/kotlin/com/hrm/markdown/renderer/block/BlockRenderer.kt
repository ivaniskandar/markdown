package com.hrm.markdown.renderer.block

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.renderer.LocalMarkdownTheme
import com.hrm.markdown.renderer.LocalRendererDocument

/**
 * 块级节点分发器。
 * 根据节点类型分发到对应的块级渲染器。
 */
@Composable
internal fun BlockRenderer(
    node: Node,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is Heading -> HeadingRenderer(node, modifier)
        is SetextHeading -> SetextHeadingRenderer(node, modifier)
        is Paragraph -> ParagraphRenderer(node, modifier)
        is ThematicBreak -> ThematicBreakRenderer(modifier)
        is FencedCodeBlock -> FencedCodeBlockRenderer(node, modifier)
        is IndentedCodeBlock -> IndentedCodeBlockRenderer(node, modifier)
        is BlockQuote -> BlockQuoteRenderer(node, modifier)
        is ListBlock -> ListBlockRenderer(node, modifier)
        is HtmlBlock -> HtmlBlockRenderer(node, modifier)
        is Table -> TableRenderer(node, modifier)
        is MathBlock -> MathBlockRenderer(node, modifier)
        is Admonition -> AdmonitionRenderer(node, modifier)
        is CustomContainer -> CustomContainerRenderer(node, modifier)
        is DiagramBlock -> DiagramBlockRenderer(node, modifier)
        is DefinitionList -> DefinitionListRenderer(node, modifier)
        is FootnoteDefinition -> FootnoteDefinitionRenderer(node, modifier)
        is TocPlaceholder -> TocPlaceholderRenderer(modifier)
        is FrontMatter -> { /* FrontMatter 通常不渲染 */ }
        is LinkReferenceDefinition -> { /* 引用定义不直接渲染 */ }
        is AbbreviationDefinition -> { /* 缩写定义不直接渲染 */ }
        is BlankLine -> { /* 空行不渲染 */ }
        else -> {
            // 未知块级节点，尝试渲染子节点
            if (node is ContainerNode) {
                for (child in node.children) {
                    BlockRenderer(child)
                }
            }
        }
    }
}

/**
 * TOC 占位符渲染器：渲染自动生成的目录。
 */
@Composable
internal fun TocPlaceholderRenderer(
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    val document = LocalRendererDocument.current

    // 收集所有标题
    val headings = collectHeadings(document)
    if (headings.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Table of Contents",
            style = theme.headingStyles.getOrElse(2) { theme.bodyStyle },
            modifier = Modifier.padding(bottom = 4.dp),
        )
        for ((text, level, _) in headings) {
            Text(
                text = "${"  ".repeat((level - 1).coerceAtLeast(0))}• $text",
                style = theme.bodyStyle.copy(
                    color = theme.linkColor,
                    fontStyle = FontStyle.Normal,
                ),
                modifier = Modifier.padding(start = (level - 1).coerceAtLeast(0).dp * 12),
            )
        }
    }
}

private data class HeadingInfo(val text: String, val level: Int, val id: String?)

private fun collectHeadings(document: Document): List<HeadingInfo> {
    val result = mutableListOf<HeadingInfo>()
    for (child in document.children) {
        collectHeadingsRecursive(child, result)
    }
    return result
}

private fun collectHeadingsRecursive(node: Node, result: MutableList<HeadingInfo>) {
    when (node) {
        is Heading -> {
            val text = node.children.joinToString("") { extractText(it) }
            result.add(HeadingInfo(text, node.level, node.id))
        }
        is SetextHeading -> {
            val text = node.children.joinToString("") { extractText(it) }
            result.add(HeadingInfo(text, node.level, node.id))
        }
        is ContainerNode -> {
            for (child in node.children) {
                collectHeadingsRecursive(child, result)
            }
        }
        else -> {}
    }
}

private fun extractText(node: Node): String = when (node) {
    is com.hrm.markdown.parser.ast.Text -> node.literal
    is InlineCode -> node.literal
    is EscapedChar -> node.literal
    is HtmlEntity -> node.resolved.ifEmpty { node.literal }
    is Emoji -> node.literal
    is ContainerNode -> node.children.joinToString("") { extractText(it) }
    else -> ""
}
