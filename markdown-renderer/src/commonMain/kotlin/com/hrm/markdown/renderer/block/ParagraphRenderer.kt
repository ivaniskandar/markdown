package com.hrm.markdown.renderer.block

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.renderer.LocalMarkdownTheme
import com.hrm.markdown.renderer.LocalOnLinkClick
import com.hrm.markdown.renderer.inline.rememberInlineContent

/**
 * 段落渲染器。
 * 将段落的行内子节点渲染为带样式的富文本。
 */
@Composable
internal fun ParagraphRenderer(
    node: Paragraph,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    val onLinkClick = LocalOnLinkClick.current
    val (annotated, inlineContents) = rememberInlineContent(node, onLinkClick)

    BasicText(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = theme.bodyStyle,
        inlineContent = inlineContents,
    )
}
