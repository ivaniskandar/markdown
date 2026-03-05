package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.LineRange

/**
 * 表示对源文本的编辑操作。
 */
sealed class EditOperation {
    /**
     * 在指定偏移量处插入文本。
     */
    data class Insert(
        val offset: Int,
        val text: String
    ) : EditOperation()

    /**
     * 删除范围 [startOffset, endOffset) 内的文本。
     */
    data class Delete(
        val startOffset: Int,
        val endOffset: Int
    ) : EditOperation()

    /**
     * 用新文本替换范围 [startOffset, endOffset) 内的文本。
     */
    data class Replace(
        val startOffset: Int,
        val endOffset: Int,
        val newText: String
    ) : EditOperation()
}

/**
 * 分析编辑后哪些行发生了变化的结果。
 */
data class LineChangeResult(
    /** 被修改的行。 */
    val dirtyRange: LineRange,
    /** 增加（正数）或减少（负数）的行数。 */
    val lineDelta: Int,
    /** 编辑后位置的偏移量变化。 */
    val offsetDelta: Int
)

/**
 * 分析编辑操作以确定受影响的行。
 */
object EditAnalyzer {

    /**
     * 分析针对原始源文本的编辑操作，
     * 确定哪些行是脏行。
     */
    fun analyze(
        originalText: String,
        lineOffsets: IntArray,
        edit: EditOperation
    ): LineChangeResult {
        return when (edit) {
            is EditOperation.Insert -> analyzeInsert(originalText, lineOffsets, edit)
            is EditOperation.Delete -> analyzeDelete(originalText, lineOffsets, edit)
            is EditOperation.Replace -> analyzeReplace(originalText, lineOffsets, edit)
        }
    }

    private fun analyzeInsert(
        text: String,
        lineOffsets: IntArray,
        edit: EditOperation.Insert
    ): LineChangeResult {
        val startLine = findLine(lineOffsets, edit.offset)
        val newLines = edit.text.count { it == '\n' }
        val endLine = startLine + 1 + newLines

        return LineChangeResult(
            dirtyRange = LineRange(startLine, endLine),
            lineDelta = newLines,
            offsetDelta = edit.text.length
        )
    }

    private fun analyzeDelete(
        text: String,
        lineOffsets: IntArray,
        edit: EditOperation.Delete
    ): LineChangeResult {
        val startLine = findLine(lineOffsets, edit.startOffset)
        val endLine = findLine(lineOffsets, edit.endOffset - 1) + 1
        val deletedText = text.substring(edit.startOffset, edit.endOffset)
        val deletedNewlines = deletedText.count { it == '\n' }

        return LineChangeResult(
            dirtyRange = LineRange(startLine, endLine),
            lineDelta = -deletedNewlines,
            offsetDelta = -(edit.endOffset - edit.startOffset)
        )
    }

    private fun analyzeReplace(
        text: String,
        lineOffsets: IntArray,
        edit: EditOperation.Replace
    ): LineChangeResult {
        val startLine = findLine(lineOffsets, edit.startOffset)
        val origEndLine = findLine(lineOffsets, (edit.endOffset - 1).coerceAtLeast(edit.startOffset)) + 1

        val deletedText = text.substring(edit.startOffset, edit.endOffset)
        val deletedNewlines = deletedText.count { it == '\n' }
        val insertedNewlines = edit.newText.count { it == '\n' }
        val lineDelta = insertedNewlines - deletedNewlines
        val endLine = origEndLine + lineDelta

        return LineChangeResult(
            dirtyRange = LineRange(startLine, maxOf(endLine, startLine + 1)),
            lineDelta = lineDelta,
            offsetDelta = edit.newText.length - (edit.endOffset - edit.startOffset)
        )
    }

    private fun findLine(lineOffsets: IntArray, offset: Int): Int {
        var lo = 0
        var hi = lineOffsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineOffsets[mid] <= offset) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return (lo - 1).coerceAtLeast(0)
    }
}
