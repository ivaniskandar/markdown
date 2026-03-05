package com.hrm.markdown.parser.block

import com.hrm.markdown.parser.SourcePosition
import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.core.CharacterUtils
import com.hrm.markdown.parser.core.LineCursor
import com.hrm.markdown.parser.core.SourceText

/**
 * 高性能块级解析器。
 *
 * 实现 CommonMark 两遍块解析算法：
 * 1. 第一遍：逐行处理，构建块结构。
 *    每行测试是否延续已打开的块，或者开启新块。
 * 2. 第二遍：在叶子块（段落、标题）中解析行内内容。
 *
 * 此设计支持高效的增量解析：仅需重新解析脏块，
 * 且行内解析是延迟执行的。
 */
class BlockParser(
    private val source: SourceText,
    private val inlineParserFactory: ((Document) -> InlineParserInterface)? = null
) {
    private val document = Document()
    private val openBlocks = mutableListOf<OpenBlock>()
    private var currentLine = 0

    /**
     * 表示解析过程中一个已打开（尚未关闭）的块。
     */
    private class OpenBlock(
        val node: Node,
        var lastLineIndex: Int = 0,
        var contentLines: MutableList<String> = mutableListOf(),
        var contentStartLine: Int = 0,
    ) {
        var paragraphContent: StringBuilder? = null
        var isFenced: Boolean = false
        var fenceChar: Char = ' '
        var fenceLength: Int = 0
        var fenceIndent: Int = 0
        var htmlType: Int = 0
        var blankLineCount: Int = 0
    }

    /**
     * 行内解析的接口，用于后续注入。
     */
    fun interface InlineParserInterface {
        fun parseInlines(content: String, parent: ContainerNode)
    }

    /**
     * 解析整个源文本并返回文档 AST。
     */
    fun parse(): Document {
        openBlocks.clear()
        openBlocks.add(OpenBlock(document, contentStartLine = 0))
        document.clearChildren()
        document.linkDefinitions.clear()
        document.footnoteDefinitions.clear()

        for (lineIdx in 0 until source.lineCount) {
            currentLine = lineIdx
            processLine(source.lineContent(lineIdx), lineIdx)
        }

        // 关闭所有已打开的块
        while (openBlocks.size > 1) {
            finalizeBlock(openBlocks.last())
            openBlocks.removeAt(openBlocks.size - 1)
        }
        finalizeBlock(openBlocks[0])

        // 设置文档范围
        document.lineRange = LineRange(0, source.lineCount)
        document.sourceRange = SourceRange(
            SourcePosition(0, 0, 0),
            SourcePosition(
                source.lineCount - 1,
                source.lineContent(source.lineCount - 1).length,
                source.length
            )
        )

        // 解析行内内容
        parseInlineContent(document)

        return document
    }

    /**
     * 解析 [startLine, endLine) 范围内的行并返回解析后的块。
     * 由增量解析器用于重新解析脏区域。
     */
    fun parseLines(startLine: Int, endLine: Int): List<Node> {
        openBlocks.clear()
        val tempDoc = Document()
        openBlocks.add(OpenBlock(tempDoc, contentStartLine = startLine))

        for (lineIdx in startLine until endLine) {
            currentLine = lineIdx
            processLine(source.lineContent(lineIdx), lineIdx)
        }

        while (openBlocks.size > 1) {
            finalizeBlock(openBlocks.last())
            openBlocks.removeAt(openBlocks.size - 1)
        }
        finalizeBlock(openBlocks[0])

        parseInlineContent(tempDoc)

        return tempDoc.children.toList()
    }

    private fun processLine(line: String, lineIdx: Int) {
        val cursor = LineCursor(line)

        // 第一阶段：尝试延续现有已打开的块
        var matchedDepth = 1 // Document 始终匹配
        var closedByFenceOrMath = false
        for (i in 1 until openBlocks.size) {
            val ob = openBlocks[i]
            if (continueBlock(ob, cursor)) {
                matchedDepth = i + 1
            } else {
                // 检查块是否被关闭围栏/定界符关闭
                // （围栏代码块、数学块或前置元数据）
                if (ob.node is FencedCodeBlock || ob.node is MathBlock) {
                    closedByFenceOrMath = true
                }
                break
            }
        }

        // 第二阶段：关闭未匹配的块（从最深层到 matchedDepth）
        while (openBlocks.size > matchedDepth) {
            val closed = openBlocks.removeAt(openBlocks.size - 1)
            finalizeBlock(closed)
        }

        // 如果围栏块被其关闭定界符关闭，则整行已被消耗
        if (closedByFenceOrMath) return

        // 第三阶段：尝试开启新块
        var lastMatched = openBlocks.last()
        var blockStarted = true
        while (blockStarted) {
            blockStarted = false

            // 处理空行
            if (cursor.restIsBlank()) {
                handleBlankLine(lastMatched, lineIdx)
                lastMatched.lastLineIndex = lineIdx
                return
            }

            // 仅对容器块和段落尝试开启新块
            if (lastMatched.node !is ContainerNode && lastMatched.node !is Paragraph) break
            if (lastMatched.isFenced) break // 在围栏代码块内部，不开启新块
            if (lastMatched.node is HtmlBlock) break
            if (lastMatched.node is IndentedCodeBlock) break

            // 按优先级顺序尝试各种块开启
            val newBlock = tryStartBlock(cursor, lineIdx, lastMatched)
            if (newBlock != null) {
                // 如果当前是段落且新块不能中断段落
                if (lastMatched.paragraphContent != null && !canInterruptParagraph(newBlock.node, cursor)) {
                    // 不开启新块，添加到段落
                    break
                }

                // 如果当前是 ListBlock 且新块不是 ListItem，
                // 先关闭列表，使新块成为兄弟节点而非子节点
                if (lastMatched.node is ListBlock && newBlock.node !is ListItem) {
                    finalizeBlock(lastMatched)
                    openBlocks.removeAt(openBlocks.size - 1)
                    lastMatched = openBlocks.last()
                }

                // 关闭当前已打开的段落
                if (lastMatched.paragraphContent != null) {
                    val paragraphNode = lastMatched.node
                    val paragraphParent = paragraphNode.parent as? ContainerNode

                    // 对于 Setext 标题和表格，段落是被替换的，不仅仅是关闭
                    if (newBlock.node is SetextHeading || newBlock.node is Table) {
                        // 从父节点中移除段落节点，因为它将被替换
                        paragraphParent?.removeChild(paragraphNode)
                    } else {
                        finalizeBlock(lastMatched)
                    }
                    openBlocks.removeAt(openBlocks.size - 1)
                }

                val parent = if (openBlocks.last().node is ContainerNode) {
                    openBlocks.last().node as ContainerNode
                } else {
                    // 查找最近的容器
                    var idx = openBlocks.size - 1
                    while (idx >= 0 && openBlocks[idx].node !is ContainerNode) idx--
                    if (idx >= 0) openBlocks[idx].node as ContainerNode else document
                }

                parent.appendChild(newBlock.node)
                openBlocks.add(newBlock)
                lastMatched = newBlock
                blockStarted = true
            }
        }

        // 第四阶段：将行添加到当前块
        addLineToTip(lastMatched, cursor, lineIdx)
    }

    /**
     * 检查指定的已打开块能否在当前行上继续。
     */
    private fun continueBlock(ob: OpenBlock, cursor: LineCursor): Boolean {
        return when (val node = ob.node) {
            is BlockQuote -> {
                val snap = cursor.snapshot()
                val spaces = cursor.advanceSpaces(3)
                if (!cursor.isAtEnd && cursor.peek() == '>') {
                    cursor.advance() // 跳过 '>'
                    if (!cursor.isAtEnd && cursor.peek() == ' ') {
                        cursor.advance() // 跳过可选空格
                    }
                    true
                } else {
                    cursor.restore(snap)
                    false
                }
            }
            is ListItem -> {
                if (cursor.restIsBlank()) {
                    ob.blankLineCount++
                    true
                } else {
                    val snap = cursor.snapshot()
                    val indent = cursor.advanceSpaces()
                    if (indent >= node.contentIndent) {
                        true
                    } else {
                        cursor.restore(snap)
                        false
                    }
                }
            }
            is FencedCodeBlock -> {
                ob.isFenced = true
                // 检查关闭围栏
                val snap = cursor.snapshot()
                val indent = cursor.advanceSpaces(3)
                val rest = cursor.rest()
                if (isClosingFence(rest, ob.fenceChar, ob.fenceLength)) {
                    // 标记为已关闭 - 将被最终化
                    ob.isFenced = false
                    ob.lastLineIndex = currentLine
                    return false
                }
                cursor.restore(snap)
                // 移除最多 fenceIndent 个空格
                cursor.advanceSpaces(ob.fenceIndent)
                true
            }
            is IndentedCodeBlock -> {
                if (cursor.restIsBlank()) {
                    true
                } else {
                    val snap = cursor.snapshot()
                    val indent = cursor.advanceSpaces()
                    if (indent >= 4) {
                        true
                    } else {
                        cursor.restore(snap)
                        false
                    }
                }
            }
            is HtmlBlock -> {
                // 类型 1-5：检查结束条件，匹配则关闭块
                val isEnd = checkHtmlBlockEnd(cursor.rest(), ob.htmlType)
                if (isEnd && ob.htmlType in 1..5) {
                    ob.lastLineIndex = currentLine
                    return false
                }
                // 类型 6-7：空行时由 handleBlankLine 关闭
                true
            }
            is ListBlock -> true // 列表块始终继续
            is Table -> {
                // 表格行必须包含 '|' 才能延续
                val rest = cursor.rest()
                if (rest.isBlank() || !rest.contains('|')) {
                    false
                } else {
                    true
                }
            }
            is Paragraph -> true // 段落通过懒延续单独处理
            is MathBlock -> {
                // 检查关闭 $$
                val rest = cursor.rest().trim()
                if (rest == "$$") {
                    ob.lastLineIndex = currentLine
                    return false
                }
                true
            }
            is FootnoteDefinition -> {
                // 如果行有 4+ 空格缩进则继续
                if (cursor.restIsBlank()) {
                    true
                } else {
                    val snap = cursor.snapshot()
                    val indent = cursor.advanceSpaces()
                    if (indent >= 4) {
                        true
                    } else {
                        cursor.restore(snap)
                        false
                    }
                }
            }
            // 单行块，永远不继续
            is Heading -> false
            is SetextHeading -> false
            is ThematicBreak -> false
            is BlankLine -> false
            else -> true
        }
    }

    private fun canInterruptParagraph(node: Node, cursor: LineCursor): Boolean {
        return when (node) {
            is Heading -> true
            is ThematicBreak -> true
            is BlockQuote -> true
            is FencedCodeBlock -> true
            is HtmlBlock -> (node as? HtmlBlock)?.htmlType?.let { it in 1..6 } ?: true
            is ListItem -> true
            is IndentedCodeBlock -> false // 不能中断段落
            is Table -> true
            is MathBlock -> true
            else -> true
        }
    }

    /**
     * 从当前游标位置尝试开启新块。
     */
    private fun tryStartBlock(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        val snap = cursor.snapshot()

        // 文档开头的前置元数据
        if (lineIdx == 0) {
            tryStartFrontMatter(cursor, lineIdx)?.let { return it }
            cursor.restore(snap)
        }

        // Setext 标题（根据 CommonMark 规范必须在主题分隔线之前检查，
        // 因为当前面有段落内容时 Setext 标题优先级更高）
        tryStartSetextHeading(cursor, lineIdx, tip)?.let { return it }
        cursor.restore(snap)

        // ATX 标题
        tryStartAtxHeading(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // 表格（在段落行后检查分隔行 - 在主题分隔线之前，
        // 使得 `| A |\n| --- |` 被解析为表格而非主题分隔线）
        tryStartTable(cursor, lineIdx, tip)?.let { return it }
        cursor.restore(snap)

        // 主题分隔线（必须在列表项之前检查）
        tryStartThematicBreak(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // 围栏代码块
        tryStartFencedCodeBlock(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // 数学块 ($$)
        tryStartMathBlock(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // HTML 块
        tryStartHtmlBlock(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // 块引用
        tryStartBlockQuote(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // 列表项
        tryStartListItem(cursor, lineIdx, tip)?.let { return it }
        cursor.restore(snap)

        // 脚注定义
        tryStartFootnoteDefinition(cursor, lineIdx)?.let { return it }
        cursor.restore(snap)

        // 缩进代码块（必须在列表项检查之后）
        tryStartIndentedCodeBlock(cursor, lineIdx, tip)?.let { return it }
        cursor.restore(snap)

        return null
    }

    // ────── 块开启器 ──────

    private fun tryStartAtxHeading(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd || cursor.peek() != '#') return null

        var level = 0
        while (!cursor.isAtEnd && cursor.peek() == '#') {
            cursor.advance()
            level++
        }
        if (level > 6) return null
        if (!cursor.isAtEnd && cursor.peek() != ' ' && cursor.peek() != '\t') {
            if (!cursor.isAtEnd) return null // `#heading` without space is not a heading
        }

        // 跳过 # 后面的空格
        if (!cursor.isAtEnd && (cursor.peek() == ' ' || cursor.peek() == '\t')) {
            cursor.advance()
        }

        // 获取内容，去除可选的尾部 #
        var content = cursor.rest().trimEnd()
        val customId = extractCustomId(content)
        if (customId != null) {
            content = content.replace(CUSTOM_ID_STRIP_REGEX, "").trimEnd()
        }
        // 去除尾部 #
        if (content.endsWith('#')) {
            val strippedTrailing = content.trimEnd('#')
            if (strippedTrailing.isEmpty() || strippedTrailing.endsWith(' ') || strippedTrailing.endsWith('\t')) {
                content = strippedTrailing.trimEnd()
            }
        }

        val heading = Heading(level)
        heading.customId = customId
        heading.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(heading, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        ob.contentLines.add(content)
        return ob
    }

    private fun extractCustomId(content: String): String? {
        val match = CUSTOM_ID_REGEX.find(content) ?: return null
        return match.groupValues[1]
    }

    private fun tryStartSetextHeading(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        if (tip.paragraphContent == null) return null

        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd) return null

        val c = cursor.peek()
        if (c != '=' && c != '-') return null

        // 所有剩余字符必须是同一个字符（加上尾部空格）
        val rest = cursor.rest()
        val stripped = rest.trimEnd()
        if (!stripped.all { it == c }) return null
        if (stripped.isEmpty()) return null

        val level = if (c == '=') 1 else 2
        val heading = SetextHeading(level)
        heading.lineRange = LineRange(tip.contentStartLine, lineIdx + 1)

        val ob = OpenBlock(heading, contentStartLine = tip.contentStartLine, lastLineIndex = lineIdx)
        ob.contentLines.addAll(tip.paragraphContent.toString().split('\n'))
        return ob
    }

    private fun tryStartThematicBreak(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd) return null

        val c = cursor.peek()
        if (c != '-' && c != '*' && c != '_') return null

        var count = 0
        val rest = cursor.rest()
        for (ch in rest) {
            when (ch) {
                c -> count++
                ' ', '\t' -> {} // 之间允许空格
                else -> return null
            }
        }

        if (count < 3) return null

        val tb = ThematicBreak(c)
        tb.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(tb, lastLineIndex = lineIdx)
        return ob
    }

    private fun tryStartFencedCodeBlock(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd) return null

        val c = cursor.peek()
        if (c != '`' && c != '~') return null

        var fenceLength = 0
        while (!cursor.isAtEnd && cursor.peek() == c) {
            cursor.advance()
            fenceLength++
        }
        if (fenceLength < 3) return null

        // 信息字符串（反引号围栏的信息中不允许包含反引号）
        val info = cursor.rest().trim()
        if (c == '`' && info.contains('`')) return null

        val language = info.split(INFO_LANG_SPLIT_REGEX).firstOrNull()?.trim() ?: ""

        val block = FencedCodeBlock(
            info = info,
            language = language,
            fenceChar = c,
            fenceLength = fenceLength,
            fenceIndent = indent
        )
        block.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(block, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        ob.isFenced = true
        ob.fenceChar = c
        ob.fenceLength = fenceLength
        ob.fenceIndent = indent
        return ob
    }

    private fun isClosingFence(line: String, fenceChar: Char, openLength: Int): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed[0] != fenceChar) return false
        if (!trimmed.all { it == fenceChar }) return false
        return trimmed.length >= openLength
    }

    private fun tryStartIndentedCodeBlock(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        // 缩进代码块不能中断段落
        if (tip.paragraphContent != null) return null
        // 必须有 4 个空格
        val indent = cursor.advanceSpaces()
        if (indent < 4) return null

        val block = IndentedCodeBlock()
        block.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(block, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        return ob
    }

    private fun tryStartBlockQuote(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd || cursor.peek() != '>') return null

        cursor.advance() // 跳过 '>'
        if (!cursor.isAtEnd && cursor.peek() == ' ') {
            cursor.advance() // 跳过可选空格
        }

        val bq = BlockQuote()
        bq.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(bq, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        return ob
    }

    private fun tryStartListItem(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        val snap = cursor.snapshot()
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd) return null

        val c = cursor.peek()
        var ordered = false
        var bulletChar = c
        var startNumber = 1
        var delimiter = '.'
        var markerWidth = 0

        when (c) {
            '-', '*', '+' -> {
                cursor.advance()
                markerWidth = 1
            }
            in '0'..'9' -> {
                ordered = true
                val numBuilder = StringBuilder()
                while (!cursor.isAtEnd && cursor.peek() in '0'..'9' && numBuilder.length < 9) {
                    numBuilder.append(cursor.advance())
                }
                if (cursor.isAtEnd) return null
                val d = cursor.peek()
                if (d != '.' && d != ')') return null
                delimiter = d
                cursor.advance()
                startNumber = numBuilder.toString().toIntOrNull() ?: return null
                markerWidth = numBuilder.length + 1
            }
            else -> return null
        }

        // 必须后跟空格/制表符或行尾
        if (!cursor.isAtEnd && cursor.peek() != ' ' && cursor.peek() != '\t') return null

        // 消耗标记后的一个空格（或使用行尾标记）
        val contentIndent = indent + markerWidth + if (!cursor.isAtEnd) {
            // 消耗标记后的空格（1 到 4 个用于内容缩进）
            val postMarker = cursor.advanceSpaces(4)
            if (postMarker == 0) 1 else postMarker
        } else {
            1
        }

        // 检查任务列表
        var isTask = false
        var checked = false
        if (!cursor.isAtEnd) {
            val taskSnap = cursor.snapshot()
            if (cursor.peek() == '[') {
                cursor.advance()
                if (!cursor.isAtEnd) {
                    val mark = cursor.peek()
                    if (mark == ' ' || mark == 'x' || mark == 'X') {
                        cursor.advance()
                        if (!cursor.isAtEnd && cursor.peek() == ']') {
                            cursor.advance()
                            if (cursor.isAtEnd || cursor.peek() == ' ' || cursor.peek() == '\t') {
                                isTask = true
                                checked = mark == 'x' || mark == 'X'
                                if (!cursor.isAtEnd) cursor.advance() // 跳过 ] 后的空格
                            } else {
                                cursor.restore(taskSnap)
                            }
                        } else {
                            cursor.restore(taskSnap)
                        }
                    } else {
                        cursor.restore(taskSnap)
                    }
                } else {
                    cursor.restore(taskSnap)
                }
            }
        }

        val listItem = ListItem(
            markerIndent = indent,
            contentIndent = contentIndent
        )
        listItem.taskListItem = isTask
        listItem.checked = checked
        listItem.lineRange = LineRange(lineIdx, lineIdx + 1)

        // 检查是否需要创建新列表或添加到现有列表
        val parentOb = if (openBlocks.isNotEmpty()) openBlocks.last() else null
        val parentList = parentOb?.node as? ListBlock

        if (parentList == null || !listsMatch(parentList, ordered, bulletChar, delimiter)) {
            // 如果存在不匹配的列表，先关闭它
            if (parentList != null && !listsMatch(parentList, ordered, bulletChar, delimiter)) {
                finalizeBlock(parentOb!!)
                openBlocks.removeAt(openBlocks.size - 1)
            }

            // 创建新列表
            val list = ListBlock(
                ordered = ordered,
                bulletChar = bulletChar,
                startNumber = startNumber,
                delimiter = delimiter
            )
            list.lineRange = LineRange(lineIdx, lineIdx + 1)

            val container = findNearestContainer()
            container.appendChild(list)
            val listOb = OpenBlock(list, contentStartLine = lineIdx, lastLineIndex = lineIdx)
            openBlocks.add(listOb)
        }

        val ob = OpenBlock(listItem, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        return ob
    }

    private fun listsMatch(list: ListBlock, ordered: Boolean, bulletChar: Char, delimiter: Char): Boolean {
        if (list.ordered != ordered) return false
        if (ordered) return list.delimiter == delimiter
        return list.bulletChar == bulletChar
    }

    private fun tryStartHtmlBlock(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd || cursor.peek() != '<') return null

        val rest = cursor.rest()
        val htmlType = detectHtmlBlockType(rest) ?: return null

        val block = HtmlBlock(htmlType = htmlType)
        block.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(block, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        ob.htmlType = htmlType
        return ob
    }

    private fun detectHtmlBlockType(line: String): Int? {
        val lower = line.lowercase()
        // 类型 1：<script>、<pre>、<style>、<textarea>
        if (HTML_TYPE1_REGEX.containsMatchIn(line)) return 1
        // 类型 2：<!-- 注释
        if (lower.startsWith("<!--")) return 2
        // 类型 3：<? 处理指令
        if (lower.startsWith("<?")) return 3
        // 类型 4：<!声明
        if (HTML_TYPE4_REGEX.containsMatchIn(line)) return 4
        // 类型 5：CDATA
        if (lower.startsWith("<![cdata[")) return 5
        // 类型 6：已知块级标签
        val tagMatch = HTML_TYPE6_TAG_REGEX.find(line)
        if (tagMatch != null && tagMatch.groupValues[1].lowercase() in BLOCK_TAGS) return 6
        // 类型 7：其他标签（开标签或闭标签）
        if (HTML_TYPE7_REGEX.containsMatchIn(line)) return 7
        return null
    }

    private fun checkHtmlBlockEnd(line: String, htmlType: Int): Boolean {
        val lower = line.lowercase()
        return when (htmlType) {
            1 -> lower.contains("</script>") || lower.contains("</pre>") ||
                    lower.contains("</style>") || lower.contains("</textarea>")
            2 -> line.contains("-->")
            3 -> line.contains("?>")
            4 -> line.contains(">")
            5 -> line.contains("]]>")
            6, 7 -> CharacterUtils.isBlank(line)
            else -> false
        }
    }

    private fun tryStartTable(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        if (tip.paragraphContent == null) return null
        val headerLine = tip.paragraphContent.toString().trim()
        if (!headerLine.contains('|')) return null
        // 检查当前行是否为有效的分隔行
        val delimLine = cursor.rest().trim()
        val alignments = parseTableDelimiterRow(delimLine) ?: return null
        val headerCells = parseTableRow(headerLine)
        if (headerCells.isEmpty()) return null

        val table = Table()
        table.columnAlignments = alignments
        table.lineRange = LineRange(tip.contentStartLine, lineIdx + 1)

        val head = TableHead()
        val headerRow = TableRow()
        for ((i, cellContent) in headerCells.withIndex()) {
            val align = if (i < alignments.size) alignments[i] else Table.Alignment.NONE
            val cell = TableCell(alignment = align, isHeader = true)
            cell.lineRange = LineRange(tip.contentStartLine, tip.contentStartLine + 1)
            // 单元格内容将在后续作为行内内容解析
            val ob = OpenBlock(cell)
            ob.contentLines.add(cellContent)
            headerRow.appendChild(cell)
        }
        head.appendChild(headerRow)
        table.appendChild(head)

        val body = TableBody()
        table.appendChild(body)

        val ob = OpenBlock(table, contentStartLine = tip.contentStartLine, lastLineIndex = lineIdx)
        ob.contentLines.clear()
        return ob
    }

    private fun parseTableDelimiterRow(line: String): List<Table.Alignment>? {
        val trimmed = line.trim().let { if (it.startsWith('|')) it.drop(1) else it }
            .let { if (it.endsWith('|')) it.dropLast(1) else it }
        if (trimmed.isBlank()) return null

        val cells = trimmed.split('|')
        if (cells.isEmpty()) return null

        val alignments = mutableListOf<Table.Alignment>()
        for (cell in cells) {
            val c = cell.trim()
            if (!c.matches(TABLE_DELIM_CELL_REGEX)) return null
            val left = c.startsWith(':')
            val right = c.endsWith(':')
            alignments.add(
                when {
                    left && right -> Table.Alignment.CENTER
                    right -> Table.Alignment.RIGHT
                    left -> Table.Alignment.LEFT
                    else -> Table.Alignment.NONE
                }
            )
        }
        return alignments
    }

    private fun parseTableRow(line: String): List<String> {
        val trimmed = line.trim()
        val stripped = if (trimmed.startsWith('|')) trimmed.drop(1) else trimmed
        val end = if (stripped.endsWith('|')) stripped.dropLast(1) else stripped

        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in end) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                c == '\\' -> {
                    current.append(c)
                    escaped = true
                }
                c == '|' -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    private fun tryStartMathBlock(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.remaining < 2) return null
        if (cursor.peek() != '$' || cursor.peek(1) != '$') return null

        cursor.advance()
        cursor.advance()
        val rest = cursor.rest().trim()
        // 如果同一行有内容且以 $$ 结尾，则为单行数学块
        if (rest.endsWith("$$") && rest.length > 2) {
            val content = rest.dropLast(2)
            val block = MathBlock(literal = content)
            block.lineRange = LineRange(lineIdx, lineIdx + 1)
            return OpenBlock(block, lastLineIndex = lineIdx)
        }

        val block = MathBlock()
        block.lineRange = LineRange(lineIdx, lineIdx + 1)
        val ob = OpenBlock(block, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        if (rest.isNotEmpty()) {
            ob.contentLines.add(rest)
        }
        return ob
    }

    private fun tryStartFootnoteDefinition(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val snap = cursor.snapshot()
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd || cursor.peek() != '[') return null
        cursor.advance()
        if (cursor.isAtEnd || cursor.peek() != '^') return null
        cursor.advance()

        val label = StringBuilder()
        while (!cursor.isAtEnd && cursor.peek() != ']') {
            label.append(cursor.advance())
        }
        if (cursor.isAtEnd || label.isEmpty()) return null
        cursor.advance() // 跳过 ']'
        if (cursor.isAtEnd || cursor.peek() != ':') return null
        cursor.advance() // 跳过 ':'

        // 跳过可选空格
        if (!cursor.isAtEnd && cursor.peek() == ' ') cursor.advance()

        val fd = FootnoteDefinition(label = label.toString())
        fd.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(fd, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        return ob
    }

    private fun tryStartFrontMatter(cursor: LineCursor, lineIdx: Int): OpenBlock? {
        val rest = cursor.rest().trim()
        val format = when {
            rest == "---" -> "yaml"
            rest == "+++" -> "toml"
            else -> return null
        }

        // 前置元数据需要在文档中某处有关闭标记。
        // 向前查找关闭标记。
        val closingMarker = if (format == "yaml") "---" else "+++"
        var foundClosing = false
        for (i in lineIdx + 1 until source.lineCount) {
            if (source.lineContent(i).trim() == closingMarker) {
                foundClosing = true
                break
            }
        }
        if (!foundClosing) return null

        val block = FrontMatter(format = format)
        block.lineRange = LineRange(lineIdx, lineIdx + 1)
        val ob = OpenBlock(block, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        return ob
    }

    // ────── 行处理 ──────

    private fun addLineToTip(tip: OpenBlock, cursor: LineCursor, lineIdx: Int) {
        tip.lastLineIndex = lineIdx
        val lineContent = cursor.rest()

        when (val node = tip.node) {
            is FencedCodeBlock -> {
                tip.contentLines.add(lineContent)
            }
            is IndentedCodeBlock -> {
                tip.contentLines.add(lineContent)
            }
            is HtmlBlock -> {
                tip.contentLines.add(cursor.rest())
                // 检查结束条件
                if (checkHtmlBlockEnd(source.lineContent(lineIdx), tip.htmlType)) {
                    finalizeBlock(tip)
                    openBlocks.removeAt(openBlocks.size - 1)
                }
            }
            is Heading -> {
                // ATX 标题内容已在解析时捕获
            }
            is MathBlock -> {
                tip.contentLines.add(lineContent)
            }
            is FrontMatter -> {
                val trimmed = source.lineContent(lineIdx).trim()
                val endMarker = if (node.format == "yaml") "---" else "+++"
                if (trimmed == endMarker && lineIdx > tip.contentStartLine) {
                    // 不添加关闭标记
                    finalizeBlock(tip)
                    openBlocks.removeAt(openBlocks.size - 1)
                } else {
                    tip.contentLines.add(source.lineContent(lineIdx))
                }
            }
            is FootnoteDefinition -> {
                // 添加脚注内容
                tip.contentLines.add(lineContent)
            }
            is Table -> {
                // 作为表格行解析
                val cells = parseTableRow(source.lineContent(lineIdx))
                val body = node.children.filterIsInstance<TableBody>().firstOrNull() ?: return
                val row = TableRow()
                val alignments = node.columnAlignments
                for ((i, cellContent) in cells.withIndex()) {
                    if (i >= alignments.size) break
                    val cell = TableCell(alignment = alignments[i], isHeader = false)
                    cell.lineRange = LineRange(lineIdx, lineIdx + 1)
                    val cellOb = OpenBlock(cell)
                    cellOb.contentLines.add(cellContent)
                    row.appendChild(cell)
                }
                row.lineRange = LineRange(lineIdx, lineIdx + 1)
                body.appendChild(row)
                body.lineRange = LineRange(body.lineRange.startLine, lineIdx + 1)
                node.lineRange = LineRange(node.lineRange.startLine, lineIdx + 1)
            }
            is ListBlock, is ListItem, is BlockQuote, is Document -> {
                // 容器块：创建新段落或处理懒延续
                handleContainerLine(tip, cursor, lineIdx)
            }
            is SetextHeading -> {
                // Setext 内容已捕获
            }
            is Paragraph -> {
                // 正常情况下不应到达段落这里
                if (tip.paragraphContent == null) {
                    tip.paragraphContent = StringBuilder(lineContent)
                } else {
                    tip.paragraphContent!!.append('\n').append(lineContent)
                }
            }
            else -> {
                tip.contentLines.add(lineContent)
            }
        }
    }

    private fun handleContainerLine(tip: OpenBlock, cursor: LineCursor, lineIdx: Int) {
        val content = cursor.rest()

        // 检查该容器中是否有正在构建的段落
        if (openBlocks.last() === tip) {
            // 在当前块创建新段落
            val para = Paragraph()
            para.lineRange = LineRange(lineIdx, lineIdx + 1)
            val container = tip.node as ContainerNode
            container.appendChild(para)

            val paraOb = OpenBlock(para, contentStartLine = lineIdx, lastLineIndex = lineIdx)
            paraOb.paragraphContent = StringBuilder(content)
            openBlocks.add(paraOb)
        } else {
            // 添加到已有段落
            val last = openBlocks.last()
            if (last.paragraphContent != null) {
                last.paragraphContent!!.append('\n').append(content)
                last.lastLineIndex = lineIdx
            }
        }
    }

    private fun handleBlankLine(tip: OpenBlock, lineIdx: Int) {
        when (tip.node) {
            is IndentedCodeBlock -> {
                tip.contentLines.add("")
            }
            is FencedCodeBlock -> {
                tip.contentLines.add("")
            }
            is Paragraph -> {
                // 空行结束段落
                finalizeBlock(tip)
                openBlocks.removeAt(openBlocks.size - 1)
            }
            is HtmlBlock -> {
                if (tip.htmlType == 6 || tip.htmlType == 7) {
                    tip.contentLines.add("")
                    finalizeBlock(tip)
                    openBlocks.removeAt(openBlocks.size - 1)
                } else {
                    tip.contentLines.add("")
                }
            }
            is Table -> {
                // 空行结束表格
                finalizeBlock(tip)
                openBlocks.removeAt(openBlocks.size - 1)
            }
            is ListItem -> {
                tip.blankLineCount++
            }
            is ListBlock -> {
                // 标记空行用于松散列表检测
            }
            else -> {
                // 其他块：空行
            }
        }
    }

    // ────── 块最终化 ──────

    private fun finalizeBlock(ob: OpenBlock) {
        val node = ob.node
        when (node) {
            is Paragraph -> {
                val content = ob.paragraphContent?.toString()?.trim() ?: ""
                if (content.isEmpty()) {
                    // 移除空段落
                    (node.parent as? ContainerNode)?.removeChild(node)
                    return
                }
                // 尝试从段落中提取链接引用定义
                val remaining = extractLinkReferenceDefs(content)
                if (remaining.isBlank()) {
                    (node.parent as? ContainerNode)?.removeChild(node)
                } else {
                    ob.contentLines.clear()
                    ob.contentLines.add(remaining)
                }
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is Heading -> {
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is SetextHeading -> {
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is FencedCodeBlock -> {
                node.literal = ob.contentLines.joinToString("\n")
                if (node.literal.endsWith('\n')) {
                    // 尾部换行符没问题
                } else if (ob.contentLines.isNotEmpty()) {
                    node.literal += "\n"
                }
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is IndentedCodeBlock -> {
                // 去除尾部空行
                while (ob.contentLines.isNotEmpty() && ob.contentLines.last().isBlank()) {
                    ob.contentLines.removeAt(ob.contentLines.size - 1)
                }
                node.literal = ob.contentLines.joinToString("\n") + "\n"
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is HtmlBlock -> {
                node.literal = ob.contentLines.joinToString("\n")
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is MathBlock -> {
                if (node.literal.isEmpty()) {
                    node.literal = ob.contentLines.joinToString("\n")
                }
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is FrontMatter -> {
                if (node.literal.isEmpty()) {
                    // 跳过开头标记行
                    node.literal = ob.contentLines.drop(1).joinToString("\n")
                }
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is BlockQuote -> {
                // 检查是否为警告块
                checkAdmonition(node)
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is ListBlock -> {
                // 判断紧凑 vs 松散
                node.tight = isListTight(node)
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is ListItem -> {
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is FootnoteDefinition -> {
                document.footnoteDefinitions[node.label] = node
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is Table -> {
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
            is Document -> {
                // 无特殊处理
            }
            else -> {
                node.lineRange = LineRange(ob.contentStartLine, ob.lastLineIndex + 1)
            }
        }

        // Tree-sitter 风格：为节点计算内容哈希，用于增量复用
        if (node !is Document && node.lineRange.lineCount > 0) {
            node.contentHash = source.contentHash(node.lineRange)
        }
    }

    private fun checkAdmonition(blockQuote: BlockQuote) {
        val firstChild = blockQuote.children.firstOrNull()
        if (firstChild !is Paragraph) return
        // 查找该段落对应的 OpenBlock 获取其内容
        // 检查原始文本内容是否匹配 [!TYPE] 模式
        // 这将在行内解析阶段处理
    }

    private fun isListTight(list: ListBlock): Boolean {
        for (item in list.children) {
            if (item !is ListItem) continue
            // 如果任何列表项的子节点之间有空行则为松散列表
            val children = item.children
            for (i in 0 until children.size - 1) {
                val thisEnd = children[i].lineRange.endLine
                val nextStart = children[i + 1].lineRange.startLine
                if (nextStart > thisEnd) return false
            }
        }
        // 同时检查列表项之间
        val items = list.children
        for (i in 0 until items.size - 1) {
            if (items[i] is ListItem && items[i + 1] is ListItem) {
                val thisEnd = items[i].lineRange.endLine
                val nextStart = items[i + 1].lineRange.startLine
                if (nextStart > thisEnd) return false
            }
        }
        return true
    }

    /**
     * 从段落内容中提取链接引用定义。
     * 返回不属于链接引用定义的剩余内容。
     */
    private fun extractLinkReferenceDefs(content: String): String {
        var remaining = content
        while (true) {
            val match = LINK_REF_DEF_REGEX.find(remaining) ?: break
            if (match.range.first != 0) break

            val label = CharacterUtils.normalizeLinkLabel(match.groupValues[1])
            val destination = match.groupValues[2].let {
                if (it.startsWith('<') && it.endsWith('>')) it.drop(1).dropLast(1) else it
            }
            val title = match.groupValues[4].ifEmpty { null }

            if (label.isNotEmpty() && !document.linkDefinitions.containsKey(label)) {
                val def = LinkReferenceDefinition(
                    label = label,
                    destination = destination,
                    title = title
                )
                document.linkDefinitions[label] = def
                document.appendChild(def)
            }

            remaining = remaining.substring(match.range.last + 1).trimStart('\n')
        }
        return remaining
    }

    private fun findNearestContainer(): ContainerNode {
        for (i in openBlocks.indices.reversed()) {
            val node = openBlocks[i].node
            if (node is ContainerNode) return node
        }
        return document
    }

    // ────── 行内解析 ──────

    private fun parseInlineContent(doc: Document) {
        val inlineParser = inlineParserFactory?.invoke(doc) ?: return
        parseInlineContentRecursive(doc, inlineParser)
    }

    private fun parseInlineContentRecursive(node: Node, inlineParser: InlineParserInterface) {
        when (node) {
            is Paragraph -> {
                // Find the OpenBlock content or reconstruct from source lines
                val content = reconstructContent(node)
                node.clearChildren()
                inlineParser.parseInlines(content, node)
            }
            is Heading -> {
                val content = reconstructContent(node)
                node.clearChildren()
                inlineParser.parseInlines(content, node)
            }
            is SetextHeading -> {
                val content = reconstructContent(node)
                node.clearChildren()
                inlineParser.parseInlines(content, node)
            }
            is TableCell -> {
                val content = reconstructContent(node)
                node.clearChildren()
                inlineParser.parseInlines(content, node)
            }
            is ContainerNode -> {
                for (child in node.children.toList()) {
                    parseInlineContentRecursive(child, inlineParser)
                }
            }
            else -> {} // 叶子节点不需要行内解析
        }
    }

    private fun reconstructContent(node: Node): String {
        // Try to reconstruct content from source lines
        val lr = node.lineRange
        if (lr.lineCount <= 0) return ""
        return when (node) {
            is Heading -> {
                // 对于 ATX 标题，内容已在解析时捕获
                val line = source.lineContent(lr.startLine)
                val stripped = line.trimStart()
                val hashes = stripped.takeWhile { it == '#' }
                if (hashes.length in 1..6) {
                    var content = stripped.drop(hashes.length)
                    if (content.startsWith(' ') || content.startsWith('\t')) {
                        content = content.drop(1)
                    }
                    // 去除尾部 #
                    content = content.trimEnd()
                    val customId = extractCustomId(content)
                    if (customId != null) {
                        content = content.replace(CUSTOM_ID_STRIP_REGEX, "").trimEnd()
                    }
                    if (content.endsWith('#')) {
                        val t = content.trimEnd('#')
                        if (t.isEmpty() || t.endsWith(' ') || t.endsWith('\t')) {
                            content = t.trimEnd()
                        }
                    }
                    content
                } else {
                    line
                }
            }
            is SetextHeading -> {
                // 内容为除最后一行（下划线）外的所有行
                val lines = (lr.startLine until lr.endLine - 1).map { source.lineContent(it) }
                lines.joinToString("\n")
            }
            is Paragraph -> {
                val lines = (lr.startLine until lr.endLine).map { source.lineContent(it).trimStart() }
                lines.joinToString("\n")
            }
            is TableCell -> {
                // 表格单元格内容来自解析后的单元格
                val lines = (lr.startLine until lr.endLine).map { source.lineContent(it) }
                lines.joinToString("\n")
            }
            else -> {
                val lines = (lr.startLine until lr.endLine).map { source.lineContent(it) }
                lines.joinToString("\n")
            }
        }
    }

    companion object {
        private val LINK_REF_DEF_REGEX = Regex(
            "^\\s{0,3}\\[([^\\]]+)\\]:\\s+(?:<([^>]*)>|(\\S+))(?:\\s+(?:\"([^\"]*)\"|'([^']*)'|\\(([^)]*)\\)))?\\s*$",
            RegexOption.MULTILINE
        )

        // ─── 预编译正则表达式（Tree-sitter 风格优化） ───

        /** 自定义标题 ID：{#id} */
        private val CUSTOM_ID_REGEX = Regex("\\{#([^\\}]+)\\}\\s*$")
        private val CUSTOM_ID_STRIP_REGEX = Regex("\\s*\\{#[^\\}]+\\}\\s*$")

        /** 围栏代码块信息字符串中的语言提取 */
        private val INFO_LANG_SPLIT_REGEX = Regex("\\s+")

        /** 表格分隔行单元格校验 */
        private val TABLE_DELIM_CELL_REGEX = Regex(":?-+:?")

        /** HTML 块类型检测（类型 1-7） */
        private val HTML_TYPE1_REGEX = Regex("^<(script|pre|style|textarea)(\\s|>|$)", RegexOption.IGNORE_CASE)
        private val HTML_TYPE4_REGEX = Regex("^<![A-Z]")
        private val HTML_TYPE6_TAG_REGEX = Regex("^</?([a-zA-Z][a-zA-Z0-9-]*)(\\s|/?>|$)")
        private val HTML_TYPE7_REGEX = Regex("^</?[a-zA-Z][a-zA-Z0-9-]*([\\s/]|>)")

        /** HTML 块类型 6 的已知块级标签集合 */
        private val BLOCK_TAGS = setOf(
            "address", "article", "aside", "base", "basefont", "blockquote", "body",
            "caption", "center", "col", "colgroup", "dd", "details", "dialog", "dir",
            "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
            "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header",
            "hr", "html", "iframe", "legend", "li", "link", "main", "menu", "menuitem",
            "nav", "noframes", "ol", "optgroup", "option", "p", "param", "search",
            "section", "summary", "table", "tbody", "td", "template", "tfoot", "th",
            "thead", "title", "tr", "track", "ul"
        )
    }
}
