package com.hrm.markdown.renderer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.BlankLine
import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.renderer.block.BlockRenderer

/**
 * Markdown 渲染器的顶层 Composable 入口。
 *
 * @param markdown 原始 Markdown 文本
 * @param modifier Compose Modifier
 * @param theme 可选的自定义主题
 * @param scrollState 滚动状态，外部可控制滚动位置
 * @param onLinkClick 链接点击回调
 */
@Composable
fun Markdown(
    markdown: String,
    modifier: Modifier = Modifier,
    theme: MarkdownTheme = MarkdownTheme(),
    scrollState: ScrollState = rememberScrollState(),
    onLinkClick: ((String) -> Unit)? = null,
) {
    val document = remember(markdown) {
        MarkdownParser().parse(markdown)
    }
    InnerMarkdown(
        document = document,
        modifier = modifier,
        theme = theme,
        scrollState = scrollState,
        onLinkClick = onLinkClick,
    )
}

/**
 * 接收已解析的 [Document] 节点进行渲染。
 * 适用于流式/增量解析场景：外部管理 MarkdownParser 实例并传入更新后的 AST。
 *
 * 典型用法：
 * ```
 * val parser = remember { MarkdownParser() }
 * var doc by remember { mutableStateOf(parser.parse("")) }
 * LaunchedEffect(Unit) {
 *     parser.beginStream()
 *     tokens.collect { chunk ->
 *         doc = parser.append(chunk)
 *     }
 *     doc = parser.endStream()
 * }
 * Markdown(document = doc, ...)
 * ```
 */
@Composable
fun Markdown(
    document: Document,
    modifier: Modifier = Modifier,
    theme: MarkdownTheme = MarkdownTheme(),
    scrollState: ScrollState = rememberScrollState(),
    onLinkClick: ((String) -> Unit)? = null,
) {
    InnerMarkdown(
        document = document,
        modifier = modifier,
        theme = theme,
        scrollState = scrollState,
        onLinkClick = onLinkClick,
    )
}

@Composable
internal fun InnerMarkdown(
    document: Document,
    modifier: Modifier = Modifier,
    theme: MarkdownTheme = MarkdownTheme(),
    scrollState: ScrollState = rememberScrollState(),
    onLinkClick: ((String) -> Unit)? = null,
) {
    val blockNodes = remember(document) {
        document.children.filter { it !is BlankLine }
    }

    ProvideMarkdownTheme(theme) {
        ProvideRendererContext(
            document = document,
            onLinkClick = onLinkClick,
        ) {
            SelectionContainer {
                Column(
                    modifier = modifier.verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(theme.blockSpacing),
                ) {
                    for (node in blockNodes) {
                        BlockRenderer(node)
                    }
                }
            }
        }
    }
}

/**
 * 非 Lazy 版本，用于嵌套在其他容器中。
 * 例如：BlockQuote、ListItem 内部的子块渲染。
 */
@Composable
internal fun MarkdownBlockChildren(
    parent: ContainerNode,
    modifier: Modifier = Modifier,
) {
    val blockNodes = remember(parent) {
        parent.children.filter { it !is BlankLine }
    }
    val theme = LocalMarkdownTheme.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(theme.blockSpacing),
    ) {
        for (node in blockNodes) {
            BlockRenderer(node)
        }
    }
}
