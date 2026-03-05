package com.hrm.markdown.parser.ast

// ─────────────── 行内级节点 ───────────────

/**
 * 纯文本内容。
 */
class Text(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitText(this)
}

/**
 * 软换行（源码中的单个换行符）。
 */
class SoftLineBreak : LeafNode() {
    override val literal: String get() = "\n"
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitSoftLineBreak(this)
}

/**
 * 硬换行（两个空格+换行，或反斜杠+换行）。
 */
class HardLineBreak : LeafNode() {
    override val literal: String get() = "\n"
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitHardLineBreak(this)
}

/**
 * 强调（斜体）：`*text*` 或 `_text_`。
 */
class Emphasis : ContainerNode() {
    var delimiter: Char = '*'
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitEmphasis(this)
}

/**
 * 加重强调（粗体）：`**text**` 或 `__text__`。
 */
class StrongEmphasis : ContainerNode() {
    var delimiter: Char = '*'
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitStrongEmphasis(this)
}

/**
 * 删除线（GFM 扩展）：`~~text~~`。
 */
class Strikethrough : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitStrikethrough(this)
}

/**
 * 行内代码：`` `code` ``。
 */
class InlineCode(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitInlineCode(this)
}

/**
 * 行内链接：`[text](url "title")`。
 */
class Link(
    var destination: String = "",
    var title: String? = null
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitLink(this)
}

/**
 * 图片：`![alt](url "title")`。
 */
class Image(
    var destination: String = "",
    var title: String? = null
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitImage(this)
}

/**
 * 自动链接：`<url>` 或 `<email>`。
 */
class Autolink(
    var destination: String = "",
    var isEmail: Boolean = false
) : LeafNode() {
    override val literal: String get() = destination
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitAutolink(this)
}

/**
 * 行内 HTML：`<tag>`、`</tag>`、`<!-- comment -->` 等。
 */
class InlineHtml(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitInlineHtml(this)
}

/**
 * HTML 实体：`&amp;`、`&#123;`、`&#x1F4A9;`。
 */
class HtmlEntity(
    override var literal: String = "",
    var resolved: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitHtmlEntity(this)
}

/**
 * 转义字符：`\*`、`\[` 等。
 */
class EscapedChar(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitEscapedChar(this)
}

/**
 * 脚注引用：`[^label]`。
 */
class FootnoteReference(
    var label: String = "",
    var index: Int = 0
) : LeafNode() {
    override val literal: String get() = label
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitFootnoteReference(this)
}

/**
 * 行内数学公式：`$...$` 或 `$$...$$`。
 */
class InlineMath(
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitInlineMath(this)
}

/**
 * 高亮：`==text==`。
 */
class Highlight : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitHighlight(this)
}

/**
 * 上标：`^text^`。
 */
class Superscript : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitSuperscript(this)
}

/**
 * 下标：`~text~`。
 */
class Subscript : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitSubscript(this)
}

/**
 * 插入文本：`++text++`。
 */
class InsertedText : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitInsertedText(this)
}

/**
 * Emoji 短代码：`:smile:`。
 */
class Emoji(
    var shortcode: String = "",
    override var literal: String = ""
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitEmoji(this)
}
