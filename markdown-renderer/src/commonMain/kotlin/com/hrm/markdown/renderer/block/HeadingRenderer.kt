package com.hrm.markdown.renderer.block

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hrm.markdown.parser.ast.Heading
import com.hrm.markdown.parser.ast.SetextHeading
import com.hrm.markdown.renderer.LocalMarkdownTheme
import com.hrm.markdown.renderer.LocalOnLinkClick
import com.hrm.markdown.renderer.LocalOnLinkClick
import com.hrm.markdown.renderer.inline.rememberInlineContent

/**
 * ATX 标题渲染器 (# ~ ######)
 */
@Composable
internal fun HeadingRenderer(
    node: Heading,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    val onLinkClick = LocalOnLinkClick.current
    val level = (node.level - 1).coerceIn(0, theme.headingStyles.lastIndex)
    val style = theme.headingStyles[level]
    val (annotated, inlineContents) = rememberInlineContent(node, onLinkClick)

    Column(modifier = modifier.fillMaxWidth()) {
        BasicText(
            text = annotated,
            style = style,
            inlineContent = inlineContents,
        )

        // h1 和 h2 下方添加分隔线（GitHub 风格）
        if (node.level <= 2) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                thickness = theme.dividerThickness,
                color = theme.dividerColor,
            )
        }
    }
}

/**
 * Setext 标题渲染器 (=== / ---)
 */
@Composable
internal fun SetextHeadingRenderer(
    node: SetextHeading,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    val onLinkClick = LocalOnLinkClick.current
    val level = (node.level - 1).coerceIn(0, theme.headingStyles.lastIndex)
    val style = theme.headingStyles[level]
    val (annotated, inlineContents) = rememberInlineContent(node, onLinkClick)

    Column(modifier = modifier.fillMaxWidth()) {
        BasicText(
            text = annotated,
            style = style,
            inlineContent = inlineContents,
        )

        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            thickness = theme.dividerThickness,
            color = theme.dividerColor,
        )
    }
}
