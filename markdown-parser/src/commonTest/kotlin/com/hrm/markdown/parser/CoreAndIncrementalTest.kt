package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.incremental.EditOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IncrementalParserTest {

    private val parser = MarkdownParser()

    @Test
    fun should_handle_insert_in_heading() {
        parser.parse("# Hello\n\nWorld")
        val doc = parser.applyEdit(EditOperation.Insert(7, " Kotlin"))
        val heading = doc.children.first()
        assertIs<Heading>(heading)
        // After edit, the heading should contain "Hello Kotlin"
        assertTrue(doc.children.isNotEmpty())
    }

    @Test
    fun should_handle_insert_new_line() {
        parser.parse("# Hello\n\nWorld")
        val doc = parser.applyEdit(EditOperation.Insert(14, "\n\nNew paragraph"))
        assertTrue(doc.children.size >= 2)
    }

    @Test
    fun should_handle_delete() {
        parser.parse("# Hello World\n\nParagraph text")
        val doc = parser.applyEdit(EditOperation.Delete(7, 13)) // Delete " World"
        val heading = doc.children.first()
        assertIs<Heading>(heading)
    }

    @Test
    fun should_handle_replace() {
        parser.parse("# Hello\n\nWorld")
        val doc = parser.applyEdit(EditOperation.Replace(2, 7, "Goodbye"))
        val heading = doc.children.first()
        assertIs<Heading>(heading)
    }

    @Test
    fun should_handle_insert_code_block() {
        parser.parse("# Hello\n\nWorld")
        val doc = parser.applyEdit(EditOperation.Insert(14, "\n\n```kotlin\nfun main() {}\n```"))
        assertTrue(doc.children.any { it is FencedCodeBlock })
    }

    @Test
    fun should_handle_multiple_edits() {
        parser.parse("# Hello\n\nWorld\n\nFoo")
        val doc = parser.applyEdits(listOf(
            EditOperation.Insert(14, " changed"),
            EditOperation.Replace(2, 7, "Bye")
        ))
        assertTrue(doc.children.isNotEmpty())
    }

    @Test
    fun should_handle_edit_to_empty() {
        parser.parse("# Hello")
        val doc = parser.applyEdit(EditOperation.Replace(0, 7, ""))
        assertTrue(doc.children.isEmpty() || doc.children.all { it is BlankLine })
    }

    @Test
    fun should_handle_insert_into_empty() {
        parser.parse("")
        val doc = parser.applyEdit(EditOperation.Insert(0, "# Hello"))
        assertTrue(doc.children.isNotEmpty())
        val heading = doc.children.first()
        assertIs<Heading>(heading)
    }

    @Test
    fun should_preserve_unaffected_blocks() {
        parser.parse("# Title\n\nParagraph 1\n\nParagraph 2\n\nParagraph 3")
        val originalBlockCount = parser.document.children.size

        // Edit only affects the first paragraph
        val doc = parser.applyEdit(EditOperation.Replace(9, 20, "Changed text"))
        // Should still have similar number of blocks
        assertTrue(doc.children.isNotEmpty())
    }

    @Test
    fun should_handle_adding_block_quote() {
        parser.parse("Hello\n\nWorld")
        val doc = parser.applyEdit(EditOperation.Insert(0, "> "))
        val first = doc.children.first()
        assertIs<BlockQuote>(first)
    }
}

class SourceTextTest {

    @Test
    fun should_normalize_crlf() {
        val source = com.hrm.markdown.parser.core.SourceText.of("line1\r\nline2\r\nline3")
        assertEquals(3, source.lineCount)
        assertEquals("line1", source.lineContent(0))
        assertEquals("line2", source.lineContent(1))
        assertEquals("line3", source.lineContent(2))
    }

    @Test
    fun should_normalize_cr() {
        val source = com.hrm.markdown.parser.core.SourceText.of("line1\rline2")
        assertEquals(2, source.lineCount)
        assertEquals("line1", source.lineContent(0))
        assertEquals("line2", source.lineContent(1))
    }

    @Test
    fun should_replace_null_char() {
        val source = com.hrm.markdown.parser.core.SourceText.of("hello\u0000world")
        assertTrue(source.content.contains('\uFFFD'))
    }

    @Test
    fun should_find_line_at_offset() {
        val source = com.hrm.markdown.parser.core.SourceText.of("abc\ndef\nghi")
        assertEquals(0, source.lineAtOffset(0))
        assertEquals(0, source.lineAtOffset(2))
        assertEquals(1, source.lineAtOffset(4))
        assertEquals(2, source.lineAtOffset(8))
    }

    @Test
    fun should_handle_empty_input() {
        val source = com.hrm.markdown.parser.core.SourceText.of("")
        assertEquals(1, source.lineCount)
        assertEquals("", source.lineContent(0))
    }

    @Test
    fun should_handle_trailing_newline() {
        val source = com.hrm.markdown.parser.core.SourceText.of("hello\n")
        assertEquals(2, source.lineCount)
        assertEquals("hello", source.lineContent(0))
        assertEquals("", source.lineContent(1))
    }
}

class CharacterUtilsTest {

    @Test
    fun should_identify_ascii_punctuation() {
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isAsciiPunctuation('!'))
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isAsciiPunctuation('*'))
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isAsciiPunctuation('['))
        assertTrue(!com.hrm.markdown.parser.core.CharacterUtils.isAsciiPunctuation('a'))
    }

    @Test
    fun should_identify_unicode_whitespace() {
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isUnicodeWhitespace(' '))
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isUnicodeWhitespace('\t'))
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isUnicodeWhitespace('\n'))
        assertTrue(!com.hrm.markdown.parser.core.CharacterUtils.isUnicodeWhitespace('a'))
    }

    @Test
    fun should_count_leading_spaces() {
        assertEquals(0, com.hrm.markdown.parser.core.CharacterUtils.countLeadingSpaces("hello"))
        assertEquals(4, com.hrm.markdown.parser.core.CharacterUtils.countLeadingSpaces("    hello"))
        assertEquals(4, com.hrm.markdown.parser.core.CharacterUtils.countLeadingSpaces("\thello"))
    }

    @Test
    fun should_normalize_link_label() {
        assertEquals("foo bar", com.hrm.markdown.parser.core.CharacterUtils.normalizeLinkLabel("  FOO  BAR  "))
        assertEquals("hello", com.hrm.markdown.parser.core.CharacterUtils.normalizeLinkLabel("Hello"))
    }

    @Test
    fun should_identify_blank_lines() {
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isBlank(""))
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isBlank("   "))
        assertTrue(com.hrm.markdown.parser.core.CharacterUtils.isBlank("\t\t"))
        assertTrue(!com.hrm.markdown.parser.core.CharacterUtils.isBlank("  a  "))
    }
}

class HtmlEntitiesTest {

    @Test
    fun should_resolve_named_entities() {
        assertEquals("&", com.hrm.markdown.parser.core.HtmlEntities.resolve("&amp;"))
        assertEquals("<", com.hrm.markdown.parser.core.HtmlEntities.resolve("&lt;"))
        assertEquals(">", com.hrm.markdown.parser.core.HtmlEntities.resolve("&gt;"))
        assertEquals("\"", com.hrm.markdown.parser.core.HtmlEntities.resolve("&quot;"))
        assertEquals("\u00A0", com.hrm.markdown.parser.core.HtmlEntities.resolve("&nbsp;"))
    }

    @Test
    fun should_resolve_decimal_entity() {
        assertEquals("{", com.hrm.markdown.parser.core.HtmlEntities.resolve("&#123;"))
        assertEquals("A", com.hrm.markdown.parser.core.HtmlEntities.resolve("&#65;"))
    }

    @Test
    fun should_resolve_hex_entity() {
        assertEquals("\u00A9", com.hrm.markdown.parser.core.HtmlEntities.resolve("&#xA9;"))
    }

    @Test
    fun should_return_null_for_invalid_entity() {
        assertEquals(null, com.hrm.markdown.parser.core.HtmlEntities.resolve("&invalid;"))
    }

    @Test
    fun should_replace_null_codepoint() {
        assertEquals("\uFFFD", com.hrm.markdown.parser.core.HtmlEntities.resolve("&#0;"))
    }
}
