package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalParser
import com.hrm.markdown.parser.inline.InlineParser

/**
 * Markdown 解析器的主入口。
 *
 * 支持完整解析和增量（基于编辑操作的）解析。
 *
 * ## 完整解析
 * ```kotlin
 * val parser = MarkdownParser()
 * val document = parser.parse("# Hello\n\nWorld")
 * ```
 *
 * ## 增量解析
 * ```kotlin
 * val parser = MarkdownParser()
 * parser.parse("# Hello\n\nWorld")
 *
 * // 用户在 "Hello" 后面输入 " Kotlin"
 * val updated = parser.applyEdit(EditOperation.Insert(7, " Kotlin"))
 * // 仅重新解析标题，而非整个文档
 * ```
 */
class MarkdownParser {

    private val incrementalParser = IncrementalParser()

    /**
     * 当前文档 AST。每次解析/编辑后更新。
     */
    val document: Document get() = incrementalParser.document

    /**
     * 当前源文本。每次解析/编辑后更新。
     */
    val sourceText: SourceText get() = incrementalParser.sourceText

    /**
     * 解析完整的 Markdown 输入文本。
     * 返回 AST 的根 Document 节点。
     */
    fun parse(input: String): Document {
        return incrementalParser.fullParse(input)
    }

    /**
     * 应用单个编辑操作并增量更新 AST。
     * 返回更新后的 Document。
     */
    fun applyEdit(edit: EditOperation): Document {
        return incrementalParser.applyEdit(edit)
    }

    /**
     * 应用多个编辑操作并增量更新 AST。
     * 编辑操作会自动按偏移量排序，从后往前依次应用。
     * 返回更新后的 Document。
     */
    fun applyEdits(edits: List<EditOperation>): Document {
        return incrementalParser.applyEdits(edits)
    }

    companion object {
        /**
         * 便捷方法：将 Markdown 输入解析为 Document AST。
         */
        fun parseToDocument(input: String): Document {
            return MarkdownParser().parse(input)
        }
    }
}
