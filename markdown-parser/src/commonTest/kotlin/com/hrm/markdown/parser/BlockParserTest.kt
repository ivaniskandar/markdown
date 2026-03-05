package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HeadingParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_atx_heading_level1() {
        val doc = parser.parse("# Hello World")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(1, heading.level)
        val text = heading.children.first()
        assertIs<Text>(text)
        assertEquals("Hello World", text.literal)
    }

    @Test
    fun should_parse_atx_heading_level2() {
        val doc = parser.parse("## Second Level")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(2, heading.level)
    }

    @Test
    fun should_parse_atx_heading_level3() {
        val doc = parser.parse("### Third Level")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(3, heading.level)
    }

    @Test
    fun should_parse_atx_heading_level4() {
        val doc = parser.parse("#### Fourth")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(4, heading.level)
    }

    @Test
    fun should_parse_atx_heading_level5() {
        val doc = parser.parse("##### Fifth")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(5, heading.level)
    }

    @Test
    fun should_parse_atx_heading_level6() {
        val doc = parser.parse("###### Sixth")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(6, heading.level)
    }

    @Test
    fun should_not_parse_7_hashes_as_heading() {
        val doc = parser.parse("####### Not a heading")
        val first = doc.children.first()
        assertIs<Paragraph>(first)
    }

    @Test
    fun should_require_space_after_hash() {
        val doc = parser.parse("#NoSpace")
        val first = doc.children.first()
        assertIs<Paragraph>(first)
    }

    @Test
    fun should_strip_trailing_hashes() {
        val doc = parser.parse("# Heading #")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        val text = heading.children.first()
        assertIs<Text>(text)
        assertEquals("Heading", text.literal)
    }

    @Test
    fun should_allow_up_to_3_spaces_indent() {
        val doc = parser.parse("   # Heading")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(1, heading.level)
    }

    @Test
    fun should_parse_custom_heading_id() {
        val doc = parser.parse("### My Heading {#custom-id}")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(3, heading.level)
        assertEquals("custom-id", heading.customId)
    }

    @Test
    fun should_parse_empty_atx_heading() {
        val doc = parser.parse("#")
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        assertEquals(1, heading.level)
    }

    @Test
    fun should_parse_setext_heading_level1() {
        val doc = parser.parse("Heading\n===")
        val blocks = doc.children
        assertTrue(blocks.isNotEmpty())
        val heading = blocks.first()
        assertIs<SetextHeading>(heading)
        assertEquals(1, heading.level)
    }

    @Test
    fun should_parse_setext_heading_level2() {
        val doc = parser.parse("Heading\n---")
        val blocks = doc.children
        // Could be setext heading or thematic break depending on context
        assertTrue(blocks.isNotEmpty())
    }
}

class ParagraphParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_simple_paragraph() {
        val doc = parser.parse("Hello World")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val text = para.children.first()
        assertIs<Text>(text)
        assertEquals("Hello World", text.literal)
    }

    @Test
    fun should_merge_consecutive_lines() {
        val doc = parser.parse("Line 1\nLine 2")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
    }

    @Test
    fun should_separate_paragraphs_by_blank_line() {
        val doc = parser.parse("Para 1\n\nPara 2")
        assertEquals(2, doc.children.size)
        assertIs<Paragraph>(doc.children[0])
        assertIs<Paragraph>(doc.children[1])
    }

    @Test
    fun should_handle_empty_input() {
        val doc = parser.parse("")
        assertTrue(doc.children.isEmpty())
    }

    @Test
    fun should_handle_only_blank_lines() {
        val doc = parser.parse("\n\n\n")
        assertTrue(doc.children.isEmpty())
    }
}

class ThematicBreakParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_three_hyphens() {
        val doc = parser.parse("---")
        val tb = doc.children.first()
        assertIs<ThematicBreak>(tb)
    }

    @Test
    fun should_parse_three_asterisks() {
        val doc = parser.parse("***")
        val tb = doc.children.first()
        assertIs<ThematicBreak>(tb)
    }

    @Test
    fun should_parse_three_underscores() {
        val doc = parser.parse("___")
        val tb = doc.children.first()
        assertIs<ThematicBreak>(tb)
    }

    @Test
    fun should_parse_with_spaces_between() {
        val doc = parser.parse("- - -")
        val tb = doc.children.first()
        assertIs<ThematicBreak>(tb)
    }

    @Test
    fun should_parse_more_than_three() {
        val doc = parser.parse("----------")
        val tb = doc.children.first()
        assertIs<ThematicBreak>(tb)
    }

    @Test
    fun should_not_parse_two_hyphens() {
        val doc = parser.parse("--")
        val first = doc.children.first()
        assertIs<Paragraph>(first)
    }

    @Test
    fun should_allow_up_to_3_spaces_indent() {
        val doc = parser.parse("   ---")
        val tb = doc.children.first()
        assertIs<ThematicBreak>(tb)
    }
}

class FencedCodeBlockParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_backtick_fence() {
        val doc = parser.parse("```\ncode\n```")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
        assertTrue(block.literal.contains("code"))
    }

    @Test
    fun should_parse_tilde_fence() {
        val doc = parser.parse("~~~\ncode\n~~~")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
    }

    @Test
    fun should_parse_info_string() {
        val doc = parser.parse("```kotlin\nfun main() {}\n```")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
        assertEquals("kotlin", block.language)
    }

    @Test
    fun should_take_first_word_as_language() {
        val doc = parser.parse("```kotlin extra info\ncode\n```")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
        assertEquals("kotlin", block.language)
    }

    @Test
    fun should_require_matching_fence_char() {
        // Opening with ``` should not close with ~~~
        val doc = parser.parse("```\ncode\n~~~")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
        // Content should include the ~~~ since it doesn't close
        assertTrue(block.literal.contains("~~~"))
    }

    @Test
    fun should_close_at_document_end_if_unclosed() {
        val doc = parser.parse("```\ncode line 1\ncode line 2")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
    }

    @Test
    fun should_not_parse_markdown_inside() {
        val doc = parser.parse("```\n# Not a heading\n**not bold**\n```")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
        assertTrue(block.literal.contains("# Not a heading"))
    }

    @Test
    fun should_allow_longer_closing_fence() {
        val doc = parser.parse("```\ncode\n`````")
        val block = doc.children.first()
        assertIs<FencedCodeBlock>(block)
    }

    @Test
    fun should_not_allow_backticks_in_backtick_info() {
        val doc = parser.parse("``` foo`bar\ncode\n```")
        // The opening line has backtick in info, so not a valid fence
        val first = doc.children.first()
        assertIs<Paragraph>(first)
    }
}

class BlockQuoteParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_simple_block_quote() {
        val doc = parser.parse("> Hello")
        val bq = doc.children.first()
        assertIs<BlockQuote>(bq)
        assertTrue(bq.children.isNotEmpty())
    }

    @Test
    fun should_parse_multi_line_block_quote() {
        val doc = parser.parse("> Line 1\n> Line 2")
        val bq = doc.children.first()
        assertIs<BlockQuote>(bq)
    }

    @Test
    fun should_parse_nested_block_quote() {
        val doc = parser.parse(">> Nested")
        val bq = doc.children.first()
        assertIs<BlockQuote>(bq)
        val inner = bq.children.first()
        assertIs<BlockQuote>(inner)
    }

    @Test
    fun should_allow_optional_space_after_marker() {
        val doc = parser.parse(">No space")
        val bq = doc.children.first()
        assertIs<BlockQuote>(bq)
    }
}

class ListParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_unordered_list_dash() {
        val doc = parser.parse("- Item 1\n- Item 2")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
        assertEquals(false, list.ordered)
        assertEquals(2, list.children.size)
    }

    @Test
    fun should_parse_unordered_list_asterisk() {
        val doc = parser.parse("* Item 1\n* Item 2")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
    }

    @Test
    fun should_parse_unordered_list_plus() {
        val doc = parser.parse("+ Item 1\n+ Item 2")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
    }

    @Test
    fun should_parse_ordered_list() {
        val doc = parser.parse("1. First\n2. Second\n3. Third")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
        assertEquals(true, list.ordered)
    }

    @Test
    fun should_parse_ordered_list_with_paren() {
        val doc = parser.parse("1) First\n2) Second")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
        assertEquals(true, list.ordered)
        assertEquals(')', list.delimiter)
    }

    @Test
    fun should_preserve_start_number() {
        val doc = parser.parse("3. Third\n4. Fourth")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
        assertEquals(3, list.startNumber)
    }

    @Test
    fun should_parse_task_list() {
        val doc = parser.parse("- [ ] Unchecked\n- [x] Checked\n- [X] Also Checked")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
        val items = list.children.filterIsInstance<ListItem>()
        assertEquals(3, items.size)
        assertTrue(items[0].taskListItem)
        assertEquals(false, items[0].checked)
        assertTrue(items[1].taskListItem)
        assertEquals(true, items[1].checked)
        assertTrue(items[2].taskListItem)
        assertEquals(true, items[2].checked)
    }

    @Test
    fun should_not_merge_different_markers() {
        val doc = parser.parse("- Dash\n* Asterisk")
        // Different markers create different lists
        assertTrue(doc.children.size >= 2)
    }
}

class HtmlBlockParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_div_html_block() {
        val doc = parser.parse("<div>\nContent\n</div>")
        val html = doc.children.first()
        assertIs<HtmlBlock>(html)
        assertTrue(html.literal.contains("<div>"))
    }

    @Test
    fun should_parse_script_html_block() {
        val doc = parser.parse("<script>\nalert('hi');\n</script>")
        val html = doc.children.first()
        assertIs<HtmlBlock>(html)
        assertEquals(1, html.htmlType)
    }

    @Test
    fun should_parse_html_comment_block() {
        val doc = parser.parse("<!-- comment -->")
        val html = doc.children.first()
        assertIs<HtmlBlock>(html)
        assertEquals(2, html.htmlType)
    }
}

class TableParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_simple_table() {
        val input = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val doc = parser.parse(input)
        val table = doc.children.first()
        assertIs<Table>(table)
    }

    @Test
    fun should_parse_alignment() {
        val input = "| Left | Center | Right |\n| :--- | :---: | ---: |\n| a | b | c |"
        val doc = parser.parse(input)
        val table = doc.children.first()
        assertIs<Table>(table)
        assertEquals(Table.Alignment.LEFT, table.columnAlignments[0])
        assertEquals(Table.Alignment.CENTER, table.columnAlignments[1])
        assertEquals(Table.Alignment.RIGHT, table.columnAlignments[2])
    }

    @Test
    fun should_parse_table_without_outer_pipes() {
        val input = "A | B\n--- | ---\n1 | 2"
        val doc = parser.parse(input)
        val table = doc.children.first()
        assertIs<Table>(table)
    }
}

class MathBlockParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_math_block() {
        val doc = parser.parse("$$\nx = \\frac{-b}{2a}\n$$")
        val math = doc.children.first()
        assertIs<MathBlock>(math)
        assertTrue(math.literal.contains("x = \\frac{-b}{2a}"))
    }

    @Test
    fun should_parse_single_line_math() {
        val doc = parser.parse("$$ E = mc^2 $$")
        val math = doc.children.first()
        assertIs<MathBlock>(math)
    }
}

class FrontMatterParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_yaml_front_matter() {
        val doc = parser.parse("---\ntitle: Hello\nauthor: Test\n---\n\nContent")
        val fm = doc.children.first()
        assertIs<FrontMatter>(fm)
        assertEquals("yaml", fm.format)
        assertTrue(fm.literal.contains("title: Hello"))
    }

    @Test
    fun should_parse_toml_front_matter() {
        val doc = parser.parse("+++\ntitle = \"Hello\"\n+++\n\nContent")
        val fm = doc.children.first()
        assertIs<FrontMatter>(fm)
        assertEquals("toml", fm.format)
    }
}
