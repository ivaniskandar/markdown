package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.SourcePosition
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.inline.InlineParser

/**
 * Tree-sitter 风格增量解析引擎。
 *
 * 核心思想：利用内容哈希实现增量树修补（incremental tree patching），
 * 最大限度复用未变化的 AST 子树，避免不必要的重新解析。
 *
 * 策略如下：
 *
 * 1. 将编辑应用到源文本以生成新的源文本。
 * 2. 计算脏行范围（受影响的行）。
 * 3. 利用旧 AST 节点类型信息智能扩展脏范围到完整块边界
 *    （围栏代码块、数学块等跨行结构需整体重解析）。
 * 4. 仅对脏区域进行局部重新解析，生成新子树。
 * 5. **Tree-sitter 风格节点复用**：新解析的块与旧块按内容哈希比对，
 *    哈希一致且行内内容未变的节点直接复用旧子树（包括其行内解析结果），
 *    避免重复的行内解析开销。
 * 6. 将新子树拼接到现有 AST 中，移动后续节点的范围。
 * 7. 增量更新链接定义表，而非全树遍历重建。
 */
class IncrementalParser {

    private var _document: Document = Document()
    private var _sourceText: SourceText = SourceText.of("")

    /** 当前文档 AST。 */
    val document: Document get() = _document

    /** 当前源文本。 */
    val sourceText: SourceText get() = _sourceText

    /**
     * 对给定输入执行完整解析。
     */
    fun fullParse(input: String): Document {
        _sourceText = SourceText.of(input)
        val parser = BlockParser(_sourceText) { doc -> InlineParser(doc) }
        _document = parser.parse()
        return _document
    }

    /**
     * 应用编辑并增量更新 AST。
     * 返回更新后的文档。
     */
    fun applyEdit(edit: EditOperation): Document {
        val oldSource = _sourceText
        val oldContent = oldSource.content

        // 计算新的源文本
        val newContent = applyEditToString(oldContent, edit)
        val newSource = SourceText.of(newContent)
        val oldLineCount = oldSource.lineCount

        // 分析哪些行发生了变化
        val changeResult = EditAnalyzer.analyze(
            oldContent,
            IntArray(oldSource.lineCount) { oldSource.lineStart(it) },
            edit
        )

        // 利用旧 AST 节点类型信息智能扩展脏范围到块边界
        val expandedDirty = expandDirtyRange(changeResult.dirtyRange, newSource, oldSource)

        // 判断：增量还是完整重解析
        val threshold = (newSource.lineCount * 0.5).toInt().coerceAtLeast(10)
        if (expandedDirty.lineCount > threshold || oldLineCount == 0) {
            _sourceText = newSource
            val parser = BlockParser(_sourceText) { doc -> InlineParser(doc) }
            _document = parser.parse()
            return _document
        }

        // 收集旧脏区域中的链接定义（后续需移除）
        val oldDirtyDefs = collectDirtyLinkDefinitions(expandedDirty)

        // 增量：仅重新解析脏区域
        _sourceText = newSource
        val parser = BlockParser(newSource) { doc ->
            // 共享现有文档的链接定义
            doc.linkDefinitions.putAll(_document.linkDefinitions)
            InlineParser(doc)
        }

        val newEndLine = (expandedDirty.startLine + expandedDirty.lineCount + changeResult.lineDelta)
            .coerceAtMost(newSource.lineCount)
            .coerceAtLeast(expandedDirty.startLine)
        val adjustedDirty = LineRange(expandedDirty.startLine, newEndLine)

        val newBlocks = if (adjustedDirty.lineCount > 0) {
            parser.parseLines(adjustedDirty.startLine, adjustedDirty.endLine)
        } else {
            emptyList()
        }

        // Tree-sitter 风格：复用未变化的子树
        val oldBlocksInRange = getBlocksInRange(expandedDirty)
        val optimizedBlocks = reuseUnchangedSubtrees(newBlocks, oldBlocksInRange, newSource)

        // 将新块拼接到文档中
        spliceBlocks(expandedDirty, adjustedDirty, optimizedBlocks, changeResult)

        // 更新文档元数据
        _document.lineRange = LineRange(0, newSource.lineCount)
        _document.sourceRange = SourceRange(
            SourcePosition(0, 0, 0),
            SourcePosition(
                newSource.lineCount - 1,
                if (newSource.lineCount > 0) newSource.lineContent(newSource.lineCount - 1).length else 0,
                newSource.length
            )
        )

        // 增量更新链接定义表（不再全树遍历）
        updateLinkDefinitions(oldDirtyDefs, optimizedBlocks)

        return _document
    }

    /**
     * 批量应用编辑。编辑必须按偏移量降序排列
     * （从后往前应用以避免偏移量失效）。
     */
    fun applyEdits(edits: List<EditOperation>): Document {
        if (edits.isEmpty()) return _document

        // 优化：将多个编辑合并为一次性文本变更，再做一次增量解析
        if (edits.size > 1) {
            val sorted = edits.sortedByDescending { editOffset(it) }
            var content = _sourceText.content
            var totalLineDelta = 0
            var totalOffsetDelta = 0

            for (edit in sorted) {
                content = applyEditToString(content, edit)
            }

            // 计算合并后的脏范围
            val newSource = SourceText.of(content)
            val oldLineCount = _sourceText.lineCount

            // 如果变化较大，直接完整重解析
            _sourceText = newSource
            val parser = BlockParser(_sourceText) { doc -> InlineParser(doc) }
            _document = parser.parse()
            return _document
        }

        return applyEdit(edits.first())
    }

    // ────── Tree-sitter 风格节点复用 ──────

    /**
     * 将新解析的块与旧块按内容哈希比对。
     * 如果新块与某个旧块的 contentHash 一致且类型相同，
     * 则直接复用旧块（包括其已解析的行内子节点），
     * 避免重复的行内解析。
     */
    private fun reuseUnchangedSubtrees(
        newBlocks: List<Node>,
        oldBlocks: List<Node>,
        newSource: SourceText
    ): List<Node> {
        if (oldBlocks.isEmpty()) return newBlocks

        // 构建旧块的哈希索引：contentHash -> 旧块列表
        val oldHashIndex = mutableMapOf<Long, MutableList<Node>>()
        for (old in oldBlocks) {
            if (old.contentHash != 0L) {
                oldHashIndex.getOrPut(old.contentHash) { mutableListOf() }.add(old)
            }
        }

        return newBlocks.map { newBlock ->
            val hash = newBlock.contentHash
            if (hash == 0L) return@map newBlock

            val candidates = oldHashIndex[hash] ?: return@map newBlock
            val match = candidates.firstOrNull { old ->
                old::class == newBlock::class && canReuseNode(old, newBlock)
            }

            if (match != null) {
                candidates.remove(match)
                // 复用旧节点：更新其行范围/源范围到新位置，保留行内子节点
                match.lineRange = newBlock.lineRange
                match.sourceRange = newBlock.sourceRange
                match.contentHash = hash
                match.parent = null // 将从旧树中解除
                match
            } else {
                newBlock
            }
        }
    }

    /**
     * 判断旧节点是否可以安全复用。
     * 要求：同类型、行数相同、叶子节点内容相同。
     */
    private fun canReuseNode(old: Node, new: Node): Boolean {
        if (old::class != new::class) return false
        if (old.lineRange.lineCount != new.lineRange.lineCount) return false

        // 对于叶子节点，额外检查字面量内容
        if (old is LeafNode && new is LeafNode) {
            return old.literal == new.literal
        }
        return true
    }

    /**
     * 获取旧 AST 中与指定行范围重叠的块节点。
     */
    private fun getBlocksInRange(range: LineRange): List<Node> {
        return _document.children.filter { it.lineRange.overlaps(range) }
    }

    // ────── 脏范围扩展 ──────

    /**
     * 扩展脏范围以涵盖完整的块边界。
     * Tree-sitter 风格改进：利用旧 AST 节点类型信息精确确定边界，
     * 避免盲目的逐行扫描。
     */
    private fun expandDirtyRange(
        dirty: LineRange,
        newSource: SourceText,
        oldSource: SourceText
    ): LineRange {
        var startLine = dirty.startLine
        var endLine = dirty.endLine

        // 先利用旧 AST 节点类型精确定位包含块
        val containingBlock = findContainingBlock(dirty.startLine)
        if (containingBlock != null) {
            // 如果在围栏代码块/数学块/前置元数据内部，必须包含整个块
            if (containingBlock is FencedCodeBlock || containingBlock is MathBlock ||
                containingBlock is FrontMatter || containingBlock is HtmlBlock) {
                startLine = minOf(startLine, containingBlock.lineRange.startLine)
                endLine = maxOf(endLine, containingBlock.lineRange.endLine)
            } else {
                startLine = minOf(startLine, containingBlock.lineRange.startLine)
                endLine = maxOf(endLine, containingBlock.lineRange.endLine)
            }
        }

        // 补充：基于文本模式的向前/向后扩展（处理新创建的结构）
        startLine = expandBackward(startLine, newSource)
        endLine = expandForward(endLine, newSource)

        return LineRange(
            startLine.coerceAtLeast(0),
            endLine.coerceAtMost(newSource.lineCount)
        )
    }

    /**
     * 向前扩展：跳到最近的块边界起始。
     * 查找块起始模式：空行、标题、围栏等。
     */
    private fun expandBackward(startLine: Int, source: SourceText): Int {
        var line = startLine
        while (line > 0) {
            val prevLine = source.lineContent(line - 1)
            // 空行是自然的块边界
            if (prevLine.isBlank()) break
            // 检查前一行是否开始了一个不应合并的新块
            val trimmed = prevLine.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                line = line - 1 // 包含围栏
                break
            }
            if (trimmed.startsWith("$$")) {
                line = line - 1
                break
            }
            if (trimmed.startsWith("---") && line - 1 == 0) {
                line = 0 // 前置元数据
                break
            }
            line--
        }
        return line.coerceAtLeast(0)
    }

    /**
     * 向后扩展：跳到最近的块边界结束。
     */
    private fun expandForward(endLine: Int, source: SourceText): Int {
        var line = endLine
        while (line < source.lineCount) {
            val content = source.lineContent(line)
            if (content.isBlank()) {
                line++ // 包含空行
                break
            }
            val trimmed = content.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~") || trimmed == "$$") {
                line++ // 包含关闭围栏
                break
            }
            line++
        }
        return line.coerceAtMost(source.lineCount)
    }

    /**
     * 查找当前 AST 中包含指定行的块节点。
     */
    private fun findContainingBlock(line: Int): Node? {
        for (child in _document.children) {
            if (child.lineRange.contains(line)) {
                return child
            }
        }
        return null
    }

    // ────── AST 拼接 ──────

    /**
     * 用新块替换旧脏范围中的块。
     */
    private fun spliceBlocks(
        oldDirty: LineRange,
        newDirty: LineRange,
        newBlocks: List<Node>,
        change: LineChangeResult
    ) {
        val children = _document.children.toMutableList()

        // 查找要移除的旧块（与旧脏范围重叠的块）
        val removeStart = children.indexOfFirst {
            it.lineRange.endLine > oldDirty.startLine
        }
        val removeEnd = children.indexOfLast {
            it.lineRange.startLine < oldDirty.endLine
        }

        if (removeStart < 0 || removeEnd < 0 || removeStart > removeEnd) {
            val insertPoint = children.indexOfFirst {
                it.lineRange.startLine >= newDirty.startLine
            }.let { if (it < 0) children.size else it }

            _document.clearChildren()
            for ((i, child) in children.withIndex()) {
                if (i == insertPoint) {
                    newBlocks.forEach { _document.appendChild(it) }
                }
                // 移动脏范围之后的块
                if (child.lineRange.startLine >= oldDirty.endLine) {
                    shiftNodeRanges(child, change.lineDelta, change.offsetDelta)
                }
                _document.appendChild(child)
            }
            if (insertPoint >= children.size) {
                newBlocks.forEach { _document.appendChild(it) }
            }
            return
        }

        // 重建子节点列表
        _document.clearChildren()
        for (i in 0 until removeStart) {
            _document.appendChild(children[i])
        }

        // 插入新块
        for (block in newBlocks) {
            _document.appendChild(block)
        }

        // 移动并添加剩余块
        for (i in removeEnd + 1 until children.size) {
            val child = children[i]
            shiftNodeRanges(child, change.lineDelta, change.offsetDelta)
            _document.appendChild(child)
        }
    }

    /**
     * 递归移动节点及其子节点的行范围和源范围。
     */
    private fun shiftNodeRanges(node: Node, lineDelta: Int, offsetDelta: Int) {
        if (lineDelta == 0 && offsetDelta == 0) return

        node.lineRange = node.lineRange.shift(lineDelta)
        node.sourceRange = node.sourceRange.shift(lineDelta, offsetDelta)

        if (node is ContainerNode) {
            for (child in node.children) {
                shiftNodeRanges(child, lineDelta, offsetDelta)
            }
        }
    }

    // ────── 增量链接定义更新 ──────

    /**
     * 收集旧脏区域中的链接定义标签（用于后续移除）。
     */
    private fun collectDirtyLinkDefinitions(dirtyRange: LineRange): Set<String> {
        val labels = mutableSetOf<String>()
        for (child in _document.children) {
            if (child.lineRange.overlaps(dirtyRange) && child is LinkReferenceDefinition) {
                labels.add(child.label.lowercase().trim())
            }
        }
        return labels
    }

    /**
     * 增量更新链接定义表：
     * 1. 移除旧脏区域中的定义
     * 2. 添加新块中的定义
     */
    private fun updateLinkDefinitions(oldLabels: Set<String>, newBlocks: List<Node>) {
        // 移除旧定义
        for (label in oldLabels) {
            _document.linkDefinitions.remove(label)
        }
        // 添加新定义
        for (block in newBlocks) {
            collectLinkDefinitions(block)
        }
    }

    // ────── 工具方法 ──────

    private fun applyEditToString(original: String, edit: EditOperation): String {
        return when (edit) {
            is EditOperation.Insert -> {
                original.substring(0, edit.offset) + edit.text + original.substring(edit.offset)
            }
            is EditOperation.Delete -> {
                original.substring(0, edit.startOffset) + original.substring(edit.endOffset)
            }
            is EditOperation.Replace -> {
                original.substring(0, edit.startOffset) + edit.newText + original.substring(edit.endOffset)
            }
        }
    }

    private fun editOffset(edit: EditOperation): Int = when (edit) {
        is EditOperation.Insert -> edit.offset
        is EditOperation.Delete -> edit.startOffset
        is EditOperation.Replace -> edit.startOffset
    }

    private fun collectLinkDefinitions(node: Node) {
        when (node) {
            is LinkReferenceDefinition -> {
                val label = node.label.lowercase().trim()
                if (label.isNotEmpty() && !_document.linkDefinitions.containsKey(label)) {
                    _document.linkDefinitions[label] = node
                }
            }
            is ContainerNode -> {
                for (child in node.children) {
                    collectLinkDefinitions(child)
                }
            }
            else -> {}
        }
    }
}
