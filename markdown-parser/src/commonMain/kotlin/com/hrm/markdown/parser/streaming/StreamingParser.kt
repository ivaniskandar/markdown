package com.hrm.markdown.parser.streaming

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.SourcePosition
import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.inline.InlineParser

/**
 * 面向 LLM 流式输出的高性能增量解析器。
 *
 * 核心设计：
 * - **append-only** 输入模型，无需调用方维护偏移量。
 * - 只重新解析 **尾部脏区域**（从最后一个未关闭块的起始行到文本末尾），
 *   前面已稳定的块直接复用，每次 append 的解析代价为 O(尾部块大小)。
 * - 自动修复未关闭的语法结构（围栏代码块缺少 ` ``` `、强调缺少 `**` 等），
 *   保障即使大模型遗漏结束符也能正常展示。
 * - 通过 [contentHash] 实现细粒度的 Compose 重组优化：
 *   只有内容变化的块会触发重新渲染。
 *
 * ## 使用方式
 * ```kotlin
 * val parser = StreamingParser()
 * parser.beginStream()
 * // LLM token 到达时
 * parser.append("# Hello")
 * parser.append(" World\n\n")
 * parser.append("This is **bold")
 * val doc = parser.getDocument() // "bold" 会自动补全 **
 * parser.endStream()
 * val finalDoc = parser.getDocument() // 最终文档（不做修复）
 * ```
 */
class StreamingParser {

    // ────── 状态 ──────

    /** 完整的已累积文本 */
    private val fullText = StringBuilder()

    /** 当前文档 AST */
    private var _document: Document = Document()

    /** 当前源文本 */
    private var _sourceText: SourceText = SourceText.of("")

    /** 已稳定的块（不再变化的前缀块） */
    private var stableBlockCount: Int = 0

    /** 最后一次成功解析时的文本长度（用于检测变化） */
    private var lastParsedLength: Int = 0

    /** 最后一次已稳定的文本行数 */
    private var stableEndLine: Int = 0

    /** 是否处于流式接收中 */
    private var _isStreaming: Boolean = false

    /** 当前是否处于流式接收中 */
    val isStreaming: Boolean get() = _isStreaming

    /** 当前文档 AST */
    val document: Document get() = _document

    /** 当前源文本 */
    val sourceText: SourceText get() = _sourceText

    // ────── 公开 API ──────

    /**
     * 开始一次新的流式会话。清空之前的状态。
     */
    fun beginStream() {
        fullText.clear()
        _document = Document()
        _sourceText = SourceText.of("")
        stableBlockCount = 0
        stableEndLine = 0
        lastParsedLength = 0
        _isStreaming = true
    }

    /**
     * 追加一段文本（通常是 LLM 的一个 token 或一个 chunk）。
     * 追加后立即触发增量解析。
     *
     * @return 更新后的 Document
     */
    fun append(chunk: String): Document {
        if (chunk.isEmpty()) return _document
        fullText.append(chunk)
        return parseIncremental()
    }

    /**
     * 流结束。执行最终解析（不再做未完成块修复）。
     *
     * @return 最终的 Document
     */
    fun endStream(): Document {
        _isStreaming = false
        // 最终解析：不做 auto-close 修复
        return parseFullFinal()
    }

    /**
     * 中断流（用户取消等）。以当前状态做最终快照。
     *
     * @return 当前状态的 Document（含修复）
     */
    fun abort(): Document {
        _isStreaming = false
        return _document
    }

    /**
     * 获取当前完整文本。
     */
    fun currentText(): String = fullText.toString()

    /**
     * 对给定输入执行完整解析（非流式模式）。
     */
    fun fullParse(input: String): Document {
        fullText.clear()
        fullText.append(input)
        _isStreaming = false
        stableBlockCount = 0
        stableEndLine = 0
        lastParsedLength = 0
        return parseFullFinal()
    }

    // ────── 增量解析核心 ──────

    /**
     * append-only 增量解析。
     *
     * 策略：找到文本中最后一个"确定的块边界"（即后面跟着空行的位置），
     * 该边界之前的块可从上次全量/增量解析中复用，边界之后重新解析。
     *
     * "确定的块边界"定义：
     * - 一个空行之前，且该空行之前的所有块在上次解析中已存在且行范围未变。
     *
     * 为了保证正确性，当无法确定安全边界时（如文本末尾没有空行分隔），
     * 从最后一个确认安全的空行边界开始重新解析。
     */
    private fun parseIncremental(): Document {
        val text = fullText.toString()
        val newSource = SourceText.of(text)
        _sourceText = newSource

        if (newSource.lineCount == 0) {
            _document = Document()
            return _document
        }

        // 如果文本没变，跳过
        if (text.length == lastParsedLength) {
            return _document
        }
        lastParsedLength = text.length

        // 找到安全的重解析起点
        val reparseStart = findSafeReparseStart(newSource)

        // 解析从 reparseStart 到末尾的区域
        val parser = BlockParser(newSource) { doc ->
            doc.linkDefinitions.putAll(_document.linkDefinitions)
            InlineParser(doc)
        }

        val tailBlocks: List<Node>
        if (reparseStart >= newSource.lineCount) {
            tailBlocks = emptyList()
        } else {
            tailBlocks = parser.parseLines(reparseStart, newSource.lineCount)
        }

        // 分类：哪些新块已稳定，哪些仍在构建中
        val (nowStable, stillOpen) = classifyTailBlocks(tailBlocks, newSource)

        // 对仍在构建中的块做 auto-close 修复
        val displayBlocks = if (_isStreaming && stillOpen.isNotEmpty()) {
            autoCloseBlocks(stillOpen, newSource)
        } else {
            stillOpen
        }

        // 构建新文档
        val newDoc = Document()
        newDoc.linkDefinitions.putAll(_document.linkDefinitions)
        newDoc.footnoteDefinitions.putAll(_document.footnoteDefinitions)
        newDoc.abbreviationDefinitions.putAll(_document.abbreviationDefinitions)

        // 复用 reparseStart 之前的旧块（严格要求块的结束行 <= reparseStart）
        val oldChildren = _document.children
        val reusableCount = oldChildren.count { child ->
            child.lineRange.endLine <= reparseStart
        }
        for (i in 0 until reusableCount) {
            val child = oldChildren[i]
            child.parent = null
            newDoc.appendChild(child)
        }

        // 添加新解析的稳定块
        for (block in nowStable) {
            newDoc.appendChild(block)
        }

        // 添加修复后的展示块
        for (block in displayBlocks) {
            newDoc.appendChild(block)
        }

        // 更新稳定状态：只有被空行隔开且完全在尾部脏区域之前的块才算稳定
        val newStableCount = reusableCount + nowStable.size
        stableBlockCount = newStableCount
        stableEndLine = if (nowStable.isNotEmpty()) {
            nowStable.last().lineRange.endLine
        } else if (reusableCount > 0) {
            oldChildren[reusableCount - 1].lineRange.endLine
        } else {
            0
        }

        // 更新文档元数据
        newDoc.lineRange = LineRange(0, newSource.lineCount)
        if (newSource.lineCount > 0) {
            newDoc.sourceRange = SourceRange(
                SourcePosition(0, 0, 0),
                SourcePosition(
                    newSource.lineCount - 1,
                    newSource.lineContent(newSource.lineCount - 1).length,
                    newSource.length
                )
            )
        }

        // 增量更新链接定义
        for (block in tailBlocks) {
            collectLinkDefinitions(block, newDoc)
        }

        _document = newDoc
        return _document
    }

    /**
     * 最终全量解析（流结束或非流式模式）。
     */
    private fun parseFullFinal(): Document {
        val text = fullText.toString()
        _sourceText = SourceText.of(text)
        val parser = BlockParser(_sourceText) { doc -> InlineParser(doc) }
        _document = parser.parse()
        stableBlockCount = _document.children.size
        stableEndLine = _sourceText.lineCount
        lastParsedLength = text.length
        return _document
    }

    // ────── 安全重解析起点 ──────

    /**
     * 寻找安全的重解析起点。
     *
     * 策略：从 stableEndLine 开始，向前回退到一个"确定安全"的位置。
     * 安全位置的定义是：该位置之前的内容在之前的解析中已经产生了稳定的块，
     * 且新追加的内容不可能改变这些块的性质。
     *
     * 具体规则：
     * 1. 如果 stableEndLine 之前紧邻一个空行，那么 stableEndLine 是安全的
     *    （空行是块级元素的天然分隔符）
     * 2. 否则需要回退，找到最近的"块边界"——即空行或块级结构的起始标记
     * 3. 额外考虑特殊块结构（围栏代码块、数学块等）可能跨多行，
     *    需要检查是否处于这些结构的内部
     */
    private fun findSafeReparseStart(newSource: SourceText): Int {
        // 基准起点：上次稳定的结束行
        val proposedStart = stableEndLine.coerceAtMost(newSource.lineCount)

        if (proposedStart <= 0) return 0

        // 检查上次稳定行之前是否有明确的块分隔（空行）
        val prevLine = proposedStart - 1
        if (prevLine >= 0 && prevLine < newSource.lineCount) {
            val prevContent = newSource.lineContent(prevLine)
            if (prevContent.isBlank()) {
                // 前一行是空行，这是一个天然的块边界
                return proposedStart
            }
        }

        // 前一行不是空行 → 需要回退到包含该行的块的起始位置
        // 因为新内容可能与该行属于同一个块（如段落续行、列表继续等）
        return findBlockStartBoundary(prevLine, newSource)
    }

    /**
     * 从指定行向前查找块的起始边界。
     *
     * 向前扫描直到找到一个明确的块分隔符。
     * 返回找到的块起始行号。
     */
    private fun findBlockStartBoundary(line: Int, source: SourceText): Int {
        if (line <= 0) return 0

        var l = line
        while (l > 0) {
            val prevContent = source.lineContent(l - 1)
            // 空行是块边界
            if (prevContent.isBlank()) return l

            val trimmed = prevContent.trimStart()

            // 围栏代码块开始 → 回退到围栏行本身（因为它可能未关闭）
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return l - 1
            // 数学块开始
            if (trimmed.startsWith("$$")) return l - 1
            // Front matter
            if ((trimmed == "---" || trimmed == "+++") && l - 1 == 0) return 0

            l--
        }
        return 0
    }

    // ────── 块稳定性分类 ──────

    /**
     * 将解析出的尾部块分为"已稳定"和"仍在构建中"。
     *
     * 改进规则：
     * - 如果不在流式模式，所有块都稳定
     * - 流式模式下：
     *   - 一个块如果其 endLine 之后存在空行（即下一个块之前有空行分隔），则认为稳定
     *   - 最后一个块始终不稳定（LLM 可能还在输出）
     *   - 围栏代码块/数学块等如果未被正确关闭，也标记为不稳定
     */
    private fun classifyTailBlocks(
        blocks: List<Node>,
        source: SourceText
    ): Pair<List<Node>, List<Node>> {
        if (blocks.isEmpty()) return Pair(emptyList(), emptyList())
        if (!_isStreaming) return Pair(blocks, emptyList())

        // 最后一个块始终不稳定
        if (blocks.size == 1) {
            return Pair(emptyList(), blocks)
        }

        val stable = mutableListOf<Node>()
        val open = mutableListOf<Node>()

        for (i in blocks.indices) {
            if (i == blocks.size - 1) {
                // 最后一个块始终不稳定
                open.add(blocks[i])
            } else {
                val block = blocks[i]
                val nextBlock = blocks[i + 1]
                // 检查当前块和下一个块之间是否有空行分隔
                val gapStart = block.lineRange.endLine
                val gapEnd = nextBlock.lineRange.startLine
                val hasBlankSeparator = (gapStart < gapEnd) && hasBlankLineInRange(source, gapStart, gapEnd)
                if (hasBlankSeparator) {
                    stable.add(block)
                } else {
                    // 没有空行分隔，当前块可能被后续内容改变
                    // 从这里开始到末尾都标记为不稳定
                    for (j in i until blocks.size) {
                        open.add(blocks[j])
                    }
                    break
                }
            }
        }
        return Pair(stable, open)
    }

    /**
     * 检查 [startLine, endLine) 范围内是否有空行。
     */
    private fun hasBlankLineInRange(source: SourceText, startLine: Int, endLine: Int): Boolean {
        for (line in startLine until endLine.coerceAtMost(source.lineCount)) {
            if (source.lineContent(line).isBlank()) return true
        }
        return false
    }

    // ────── 块级自动关闭 ──────

    /**
     * 对未关闭的尾部块进行自动修复。
     *
     * 块级修复规则：
     * - FencedCodeBlock 缺少关闭围栏：直接关闭，内容为已有内容
     * - MathBlock 缺少关闭 $$：直接关闭
     * - FrontMatter 缺少关闭标记：直接关闭
     * - Paragraph/Heading 等：正常，无需特殊处理
     *
     * 行内修复：对包含行内内容的块（Paragraph、Heading 等），
     * 使用 [InlineAutoCloser] 修复未关闭的强调、链接等。
     */
    private fun autoCloseBlocks(blocks: List<Node>, source: SourceText): List<Node> {
        // 对每个块进行修复
        return blocks.map { block -> autoCloseBlock(block, source) }
    }

    private fun autoCloseBlock(block: Node, source: SourceText): Node {
        when (block) {
            is FencedCodeBlock -> {
                // 围栏代码块缺少关闭围栏已在 BlockParser 中正确处理（finalizeBlock 会收集内容）
                // 这里无需额外处理，block 已经是正确状态
                return block
            }
            is MathBlock -> {
                // 同理，MathBlock 在 finalizeBlock 中已处理
                return block
            }
            is FrontMatter -> {
                return block
            }
            is HtmlBlock -> {
                return block
            }
            is Paragraph -> {
                // 对段落的行内内容做修复
                autoCloseInlineContent(block, source)
                return block
            }
            is Heading -> {
                autoCloseInlineContent(block, source)
                return block
            }
            is SetextHeading -> {
                autoCloseInlineContent(block, source)
                return block
            }
            is BlockQuote -> {
                // 递归处理块引用的子节点
                val children = block.children.toList()
                if (children.isNotEmpty()) {
                    val lastChild = children.last()
                    val repaired = autoCloseBlock(lastChild, source)
                    if (repaired !== lastChild) {
                        block.replaceChild(lastChild, repaired)
                    }
                }
                return block
            }
            is ListBlock -> {
                val items = block.children.toList()
                if (items.isNotEmpty()) {
                    val lastItem = items.last()
                    val repaired = autoCloseBlock(lastItem, source)
                    if (repaired !== lastItem) {
                        block.replaceChild(lastItem, repaired)
                    }
                }
                return block
            }
            is ListItem -> {
                val children = block.children.toList()
                if (children.isNotEmpty()) {
                    val lastChild = children.last()
                    val repaired = autoCloseBlock(lastChild, source)
                    if (repaired !== lastChild) {
                        block.replaceChild(lastChild, repaired)
                    }
                }
                return block
            }
            is Table -> {
                // 表格行可能不完整，但 BlockParser 已经处理了行的解析
                autoCloseTableCells(block, source)
                return block
            }
            else -> return block
        }
    }

    /**
     * 对容器节点的行内内容做自动修复。
     *
     * 策略：找到节点中的行内文本，用 InlineAutoCloser 分析未关闭的结构，
     * 生成修复后缀，重新解析行内内容。
     */
    private fun autoCloseInlineContent(node: ContainerNode, source: SourceText) {
        // 从子节点中提取当前的行内文本
        val inlineText = extractInlineText(node)
        if (inlineText.isEmpty()) return

        val repairSuffix = InlineAutoCloser.buildRepairSuffix(inlineText)
        if (repairSuffix.isEmpty()) return

        // 需要重新解析行内内容（含修复后缀）
        val repairedContent = inlineText + repairSuffix
        val tempDoc = Document()
        tempDoc.linkDefinitions.putAll(_document.linkDefinitions)
        val inlineParser = InlineParser(tempDoc)
        node.clearChildren()
        inlineParser.parseInlines(repairedContent, node)
    }

    /**
     * 对表格单元格的行内内容做自动修复。
     */
    private fun autoCloseTableCells(table: Table, source: SourceText) {
        for (child in table.children) {
            if (child is ContainerNode) {
                for (row in child.children) {
                    if (row is TableRow) {
                        val cells = row.children.toList()
                        if (cells.isNotEmpty()) {
                            val lastCell = cells.last()
                            if (lastCell is TableCell) {
                                autoCloseInlineContent(lastCell, source)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 从节点子节点中提取行内文本。
     */
    private fun extractInlineText(node: ContainerNode): String {
        val sb = StringBuilder()
        for (child in node.children) {
            appendNodeText(child, sb)
        }
        return sb.toString()
    }

    private fun appendNodeText(node: Node, sb: StringBuilder) {
        when (node) {
            is Text -> sb.append(node.literal)
            is InlineCode -> sb.append("`").append(node.literal).append("`")
            is InlineMath -> sb.append("$").append(node.literal).append("$")
            is Emphasis -> {
                val d = node.delimiter
                sb.append(d)
                for (child in node.children) appendNodeText(child, sb)
                sb.append(d)
            }
            is StrongEmphasis -> {
                val d = node.delimiter.toString().repeat(2)
                sb.append(d)
                for (child in node.children) appendNodeText(child, sb)
                sb.append(d)
            }
            is Strikethrough -> {
                sb.append("~~")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("~~")
            }
            is Highlight -> {
                sb.append("==")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("==")
            }
            is Link -> {
                sb.append("[")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("](").append(node.destination)
                if (node.title != null) sb.append(" \"").append(node.title).append("\"")
                sb.append(")")
            }
            is Image -> {
                sb.append("![")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("](").append(node.destination)
                if (node.title != null) sb.append(" \"").append(node.title).append("\"")
                sb.append(")")
            }
            is EscapedChar -> sb.append("\\").append(node.literal)
            is SoftLineBreak -> sb.append("\n")
            is HardLineBreak -> sb.append("\n")
            is HtmlEntity -> sb.append(node.literal)
            is Autolink -> sb.append("<").append(node.destination).append(">")
            is ContainerNode -> {
                for (child in node.children) appendNodeText(child, sb)
            }
            is LeafNode -> sb.append(node.literal)
        }
    }

    // ────── 工具方法 ──────

    private fun collectLinkDefinitions(node: Node, doc: Document) {
        when (node) {
            is LinkReferenceDefinition -> {
                val label = node.label.lowercase().trim()
                if (label.isNotEmpty() && !doc.linkDefinitions.containsKey(label)) {
                    doc.linkDefinitions[label] = node
                }
            }
            is ContainerNode -> {
                for (child in node.children) {
                    collectLinkDefinitions(child, doc)
                }
            }
            else -> {}
        }
    }
}
