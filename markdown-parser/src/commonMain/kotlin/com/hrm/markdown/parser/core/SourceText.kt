package com.hrm.markdown.parser.core

import com.hrm.markdown.parser.LineRange

/**
 * 高性能源文本容器。
 * 对输入进行预处理：规范化行尾符、替换 NUL 字符、
 * 并构建行偏移索引以实现 O(1) 的行查找。
 *
 * Tree-sitter 风格支持：提供 [contentHash] 方法，
 * 用于计算指定行范围的内容哈希，支持增量节点复用。
 */
class SourceText private constructor(
    val content: String,
    private val lineOffsets: IntArray
) {
    val length: Int get() = content.length
    val lineCount: Int get() = lineOffsets.size

    /**
     * 获取指定行的起始偏移量（基于 0 的行索引）。
     */
    fun lineStart(line: Int): Int {
        require(line in 0 until lineCount) { "Line $line out of range [0, $lineCount)" }
        return lineOffsets[line]
    }

    /**
     * 获取指定行的结束偏移量（不包含，包括换行符）。
     */
    fun lineEnd(line: Int): Int {
        require(line in 0 until lineCount) { "Line $line out of range [0, $lineCount)" }
        return if (line + 1 < lineCount) lineOffsets[line + 1] else content.length
    }

    /**
     * 获取指定行的内容（不包含末尾换行符）。
     */
    fun lineContent(line: Int): String {
        val start = lineStart(line)
        var end = lineEnd(line)
        if (end > start && content[end - 1] == '\n') end--
        return content.substring(start, end)
    }

    /**
     * 获取范围 [startLine, endLine) 内的多行内容。
     */
    fun linesContent(startLine: Int, endLine: Int): List<String> {
        val result = ArrayList<String>(endLine - startLine)
        for (i in startLine until endLine) {
            result.add(lineContent(i))
        }
        return result
    }

    /**
     * 使用二分查找确定给定偏移量所在的行。
     */
    fun lineAtOffset(offset: Int): Int {
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

    /**
     * 获取偏移量在其所在行内的列号。
     */
    fun columnAtOffset(offset: Int): Int {
        val line = lineAtOffset(offset)
        return offset - lineOffsets[line]
    }

    operator fun get(index: Int): Char = content[index]

    fun substring(startOffset: Int, endOffset: Int): String =
        content.substring(startOffset, endOffset)

    /**
     * 计算 [range] 行范围内源文本的内容哈希。
     * 使用 FNV-1a 哈希算法，用于增量解析时快速判断内容是否变化。
     */
    fun contentHash(range: LineRange): Long {
        val startOff = lineStart(range.startLine.coerceIn(0, lineCount - 1))
        val endOff = lineEnd((range.endLine - 1).coerceIn(0, lineCount - 1))
        var hash = -3750763034362895579L // FNV offset basis
        for (i in startOff until endOff.coerceAtMost(content.length)) {
            hash = hash xor content[i].code.toLong()
            hash *= 1099511628211L // FNV prime
        }
        return hash
    }

    companion object {
        /**
         * 从原始输入创建 SourceText。
         * 规范化行尾符并替换 NUL 字符。
         */
        fun of(input: String): SourceText {
            // 规范化行尾符：\r\n -> \n，\r -> \n
            // 替换 NUL（U+0000）为 U+FFFD
            val normalized = buildString(input.length) {
                var i = 0
                while (i < input.length) {
                    val c = input[i]
                    when {
                        c == '\r' -> {
                            append('\n')
                            if (i + 1 < input.length && input[i + 1] == '\n') i++
                        }
                        c == '\u0000' -> append('\uFFFD')
                        else -> append(c)
                    }
                    i++
                }
            }

            // 构建行偏移索引
            val offsets = mutableListOf(0)
            for (i in normalized.indices) {
                if (normalized[i] == '\n' && i + 1 <= normalized.length) {
                    offsets.add(i + 1)
                }
            }

            return SourceText(normalized, offsets.toIntArray())
        }
    }
}