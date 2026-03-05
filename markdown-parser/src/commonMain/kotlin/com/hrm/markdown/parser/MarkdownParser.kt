package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.streaming.StreamingParser

/**
 * Markdown 解析器的主入口。
 *
 * 支持两种模式：
 * - **完整解析**：一次性解析整个 Markdown 文本。
 * - **流式解析**：面向 LLM 流式输出场景，支持 append-only 增量解析，
 *   自动修复未关闭的语法结构（围栏代码块、强调、链接等），保障正常展示。
 *
 * ## 完整解析
 * ```kotlin
 * val parser = MarkdownParser()
 * val document = parser.parse("# Hello\n\nWorld")
 * ```
 *
 * ## 流式解析（LLM 场景）
 * ```kotlin
 * val parser = MarkdownParser()
 * parser.beginStream()
 * parser.append("# Hello")
 * parser.append(" World\n\n")
 * parser.append("This is **bold")
 * val doc = parser.getDocument() // "bold" 会自动补全 **
 * parser.endStream()
 * ```
 */
class MarkdownParser {

    private val streamingParser = StreamingParser()

    /**
     * 当前文档 AST。每次解析/编辑后更新。
     */
    val document: Document get() = streamingParser.document

    /**
     * 当前源文本。每次解析/编辑后更新。
     */
    val sourceText: SourceText get() = streamingParser.sourceText

    /**
     * 当前是否处于流式接收中。
     */
    val isStreaming: Boolean get() = streamingParser.isStreaming

    /**
     * 解析完整的 Markdown 输入文本。
     * 返回 AST 的根 Document 节点。
     */
    fun parse(input: String): Document {
        return streamingParser.fullParse(input)
    }

    // ────── 流式解析 API ──────

    /**
     * 开始一次新的流式会话。清空之前的状态。
     */
    fun beginStream() {
        streamingParser.beginStream()
    }

    /**
     * 追加一段文本（通常是 LLM 的一个 token 或一个 chunk）。
     * 追加后立即触发增量解析，自动修复未关闭的语法结构。
     *
     * @return 更新后的 Document
     */
    fun append(chunk: String): Document {
        return streamingParser.append(chunk)
    }

    /**
     * 流结束。执行最终解析（不再做未完成块修复）。
     *
     * @return 最终的 Document
     */
    fun endStream(): Document {
        return streamingParser.endStream()
    }

    /**
     * 中断流（用户取消等）。以当前状态做最终快照。
     *
     * @return 当前状态的 Document
     */
    fun abort(): Document {
        return streamingParser.abort()
    }

    /**
     * 获取当前完整文本。
     */
    fun currentText(): String = streamingParser.currentText()

    companion object {
        /**
         * 便捷方法：将 Markdown 输入解析为 Document AST。
         */
        fun parseToDocument(input: String): Document {
            return MarkdownParser().parse(input)
        }
    }
}
