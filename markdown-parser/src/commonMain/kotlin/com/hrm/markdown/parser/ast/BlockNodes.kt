package com.hrm.markdown.parser.ast

/**
 * 文档的根节点。
 */
class Document : ContainerNode() {
    /** 解析过程中收集的链接引用定义。 */
    val linkDefinitions: MutableMap<String, LinkReferenceDefinition> = mutableMapOf()

    /** 解析过程中收集的脚注定义。 */
    val footnoteDefinitions: MutableMap<String, FootnoteDefinition> = mutableMapOf()

    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitDocument(this)
}

// ─────────────── 块级节点 ───────────────

/**
 * ATX 标题：`# heading` 到 `###### heading`。
 */
class Heading(
    var level: Int,
) : ContainerNode() {
    /** 来自 `{#id}` 语法的可选自定义 ID。 */
    var customId: String? = null

    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitHeading(this)
}

/**
 * Setext 标题：`heading\n===` 或 `heading\n---`。
 */
class SetextHeading(
    var level: Int,
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitSetextHeading(this)
}

/**
 * 段落块：连续的非空行文本。
 */
class Paragraph : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitParagraph(this)
}

/**
 * 主题分隔线（水平线）：`---`、`***` 或 `___`。
 */
class ThematicBreak(
    var char: Char = '-'
) : LeafNode() {
    override val literal: String get() = ""
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitThematicBreak(this)
}

/**
 * 围栏代码块：``` 或 ~~~。
 */
class FencedCodeBlock(
    var info: String = "",
    var language: String = "",
    var fenceChar: Char = '`',
    var fenceLength: Int = 3,
    var fenceIndent: Int = 0,
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitFencedCodeBlock(this)
}

/**
 * 缩进代码块：4 个空格或 1 个制表符缩进。
 */
class IndentedCodeBlock(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitIndentedCodeBlock(this)
}

/**
 * 块引用：以 `>` 为前缀的行。
 */
class BlockQuote : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitBlockQuote(this)
}

/**
 * 列表（有序或无序）。
 */
class ListBlock(
    var ordered: Boolean = false,
    var startNumber: Int = 1,
    var bulletChar: Char = '-',
    var delimiter: Char = '.',
    var tight: Boolean = true
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitListBlock(this)
}

/**
 * 列表中的单个列表项。
 */
class ListItem(
    var markerIndent: Int = 0,
    var contentIndent: Int = 0
) : ContainerNode() {
    /** 用于任务列表。 */
    var taskListItem: Boolean = false
    var checked: Boolean = false

    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitListItem(this)
}

/**
 * HTML 块（CommonMark 规范中的类型 1-7）。
 */
class HtmlBlock(
    var htmlType: Int = 7,
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitHtmlBlock(this)
}

/**
 * 链接引用定义：`[label]: destination "title"`。
 */
class LinkReferenceDefinition(
    var label: String = "",
    var destination: String = "",
    var title: String? = null
) : LeafNode() {
    override val literal: String get() = ""
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitLinkReferenceDefinition(this)
}

/**
 * 表格块（GFM 扩展）。
 */
class Table : ContainerNode() {
    var columnAlignments: List<Alignment> = emptyList()

    enum class Alignment { LEFT, CENTER, RIGHT, NONE }

    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitTable(this)
}

class TableHead : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitTableHead(this)
}

class TableBody : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitTableBody(this)
}

class TableRow : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitTableRow(this)
}

class TableCell(
    var alignment: Table.Alignment = Table.Alignment.NONE,
    var isHeader: Boolean = false
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitTableCell(this)
}

/**
 * 脚注定义：`[^label]: content`。
 */
class FootnoteDefinition(
    var label: String = "",
    var index: Int = 0
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitFootnoteDefinition(this)
}

/**
 * 数学公式块：`$$...$$`。
 */
class MathBlock(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitMathBlock(this)
}

/**
 * 定义列表（扩展语法）。
 */
class DefinitionList : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitDefinitionList(this)
}

class DefinitionTerm : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitDefinitionTerm(this)
}

class DefinitionDescription : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitDefinitionDescription(this)
}

/**
 * 警告/提示块：`> [!NOTE]`、`> [!WARNING]` 等。
 */
class Admonition(
    var type: String = "NOTE",
    var title: String = ""
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitAdmonition(this)
}

/**
 * 前置元数据块：YAML `---` 或 TOML `+++`。
 */
class FrontMatter(
    var format: String = "yaml",
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitFrontMatter(this)
}

/**
 * 空行节点（内部用于松散列表检测）。
 */
class BlankLine : LeafNode() {
    override val literal: String get() = ""
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitBlankLine(this)
}