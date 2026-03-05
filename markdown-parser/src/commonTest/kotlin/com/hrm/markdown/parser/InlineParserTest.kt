package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InlineParserTest {

    private val parser = MarkdownParser()

    // ────── Emphasis ──────

    @Test
    fun should_parse_star_emphasis() {
        val doc = parser.parse("*italic*")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emph = para.children.first()
        assertIs<Emphasis>(emph)
        val text = emph.children.first()
        assertIs<Text>(text)
        assertEquals("italic", text.literal)
    }

    @Test
    fun should_parse_underscore_emphasis() {
        val doc = parser.parse("_italic_")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emph = para.children.first()
        assertIs<Emphasis>(emph)
    }

    @Test
    fun should_parse_strong_emphasis() {
        val doc = parser.parse("**bold**")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val strong = para.children.first()
        assertIs<StrongEmphasis>(strong)
        val text = strong.children.first()
        assertIs<Text>(text)
        assertEquals("bold", text.literal)
    }

    @Test
    fun should_parse_double_underscore_strong() {
        val doc = parser.parse("__bold__")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val strong = para.children.first()
        assertIs<StrongEmphasis>(strong)
    }

    @Test
    fun should_parse_bold_italic() {
        val doc = parser.parse("***bold italic***")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Should contain nested emphasis + strong
        assertTrue(para.children.isNotEmpty())
    }

    @Test
    fun should_parse_bold_inside_italic() {
        val doc = parser.parse("*italic **bold** italic*")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emph = para.children.first()
        assertIs<Emphasis>(emph)
        // Should contain text, strong, text
        assertTrue(emph.children.size >= 1)
    }

    @Test
    fun should_not_parse_underscore_in_word() {
        val doc = parser.parse("foo_bar_baz")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Should be plain text, not emphasis
        val text = para.children.first()
        assertIs<Text>(text)
        assertTrue(text.literal.contains("foo_bar_baz"))
    }

    // ────── Strikethrough ──────

    @Test
    fun should_parse_strikethrough() {
        val doc = parser.parse("~~deleted~~")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val strike = para.children.first()
        assertIs<Strikethrough>(strike)
    }

    @Test
    fun should_not_parse_triple_tilde_as_strikethrough() {
        // ~~~ at the start of a line is a fenced code block, not strikethrough
        val doc = parser.parse("~~~not strikethrough~~~")
        val first = doc.children.first()
        // This is a fenced code block per CommonMark spec (~~~ is a valid fence)
        assertIs<FencedCodeBlock>(first)
    }

    // ────── Inline Code ──────

    @Test
    fun should_parse_inline_code() {
        val doc = parser.parse("`code`")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val code = para.children.first()
        assertIs<InlineCode>(code)
        assertEquals("code", code.literal)
    }

    @Test
    fun should_parse_double_backtick_code() {
        val doc = parser.parse("``code with ` backtick``")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val code = para.children.first()
        assertIs<InlineCode>(code)
        assertTrue(code.literal.contains("`"))
    }

    @Test
    fun should_strip_single_space_from_code() {
        val doc = parser.parse("`` foo ``")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val code = para.children.first()
        assertIs<InlineCode>(code)
        assertEquals("foo", code.literal)
    }

    @Test
    fun should_not_strip_only_spaces() {
        val doc = parser.parse("``  ``")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val code = para.children.first()
        assertIs<InlineCode>(code)
        assertEquals("  ", code.literal)
    }

    @Test
    fun should_collapse_newlines_in_code() {
        val doc = parser.parse("`foo\nbar`")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val code = para.children.first()
        assertIs<InlineCode>(code)
        assertEquals("foo bar", code.literal)
    }

    @Test
    fun should_treat_unmatched_backticks_as_text() {
        val doc = parser.parse("`unmatched")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val text = para.children.first()
        assertIs<Text>(text)
        assertTrue(text.literal.contains("`"))
    }

    // ────── Links ──────

    @Test
    fun should_parse_inline_link() {
        val doc = parser.parse("[text](https://example.com)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("https://example.com", link.destination)
        val text = link.children.first()
        assertIs<Text>(text)
        assertEquals("text", text.literal)
    }

    @Test
    fun should_parse_link_with_title() {
        val doc = parser.parse("[text](url \"Title\")")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("Title", link.title)
    }

    @Test
    fun should_parse_link_with_angle_brackets() {
        val doc = parser.parse("[text](<url with spaces>)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("url with spaces", link.destination)
    }

    @Test
    fun should_parse_empty_url_link() {
        val doc = parser.parse("[text]()")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("", link.destination)
    }

    @Test
    fun should_parse_autolink() {
        val doc = parser.parse("<https://example.com>")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Autolink>(link)
        assertEquals("https://example.com", link.destination)
    }

    @Test
    fun should_parse_email_autolink() {
        val doc = parser.parse("<user@example.com>")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Autolink>(link)
        assertTrue(link.isEmail)
    }

    // ────── Images ──────

    @Test
    fun should_parse_image() {
        val doc = parser.parse("![alt text](image.png)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val img = para.children.first()
        assertIs<Image>(img)
        assertEquals("image.png", img.destination)
    }

    @Test
    fun should_parse_image_with_title() {
        val doc = parser.parse("![alt](img.png \"Title\")")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val img = para.children.first()
        assertIs<Image>(img)
        assertEquals("Title", img.title)
    }

    // ────── Escapes ──────

    @Test
    fun should_parse_escaped_character() {
        val doc = parser.parse("\\*not italic\\*")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Should contain escaped chars and text, not emphasis
        assertTrue(para.children.any { it is EscapedChar })
    }

    @Test
    fun should_parse_backslash_hard_break() {
        val doc = parser.parse("line1\\\nline2")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is HardLineBreak })
    }

    // ────── HTML Entities ──────

    @Test
    fun should_parse_named_entity() {
        val doc = parser.parse("&amp;")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val entity = para.children.first()
        assertIs<HtmlEntity>(entity)
        assertEquals("&", entity.resolved)
    }

    @Test
    fun should_parse_decimal_entity() {
        val doc = parser.parse("&#123;")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val entity = para.children.first()
        assertIs<HtmlEntity>(entity)
        assertEquals("{", entity.resolved)
    }

    @Test
    fun should_parse_hex_entity() {
        val doc = parser.parse("&#xA9;")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val entity = para.children.first()
        assertIs<HtmlEntity>(entity)
        assertEquals("\u00A9", entity.resolved)
    }

    // ────── Line Breaks ──────

    @Test
    fun should_parse_hard_line_break_with_spaces() {
        val doc = parser.parse("line1  \nline2")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is HardLineBreak })
    }

    @Test
    fun should_parse_soft_line_break() {
        val doc = parser.parse("line1\nline2")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is SoftLineBreak })
    }

    // ────── Inline HTML ──────

    @Test
    fun should_parse_inline_html_tag() {
        val doc = parser.parse("text <em>emphasis</em> text")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is InlineHtml })
    }

    // ────── Inline Math ──────

    @Test
    fun should_parse_inline_math() {
        val doc = parser.parse("The formula \$E = mc^2\$ is famous")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is InlineMath })
    }

    @Test
    fun should_not_parse_dollar_after_digit() {
        val doc = parser.parse("Price is 100\$")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Should not be parsed as math
        assertTrue(para.children.none { it is InlineMath })
    }

    // ────── Highlight ──────

    @Test
    fun should_parse_highlight() {
        val doc = parser.parse("==highlighted==")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val hl = para.children.first()
        assertIs<Highlight>(hl)
    }

    // ────── Emoji ──────

    @Test
    fun should_parse_emoji_shortcode() {
        val doc = parser.parse(":smile:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("smile", emoji.shortcode)
    }
}
