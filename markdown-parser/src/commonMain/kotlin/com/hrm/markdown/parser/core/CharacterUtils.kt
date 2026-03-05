package com.hrm.markdown.parser.core

/**
 * 用于 Markdown 解析的 Unicode 字符分类工具。
 * 用于强调分隔符规则、自动链接检测等。
 */
object CharacterUtils {

    /** 可用反斜杠转义的 ASCII 标点字符。 */
    const val ESCAPABLE_CHARS = "\\`*_{}[]()#+-.!|~<>\"'/^=:;&@"

    /** 所有 ASCII 标点字符。 */
    private const val ASCII_PUNCTUATION = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    fun isAsciiPunctuation(c: Char): Boolean = c in ASCII_PUNCTUATION

    fun isEscapable(c: Char): Boolean = isAsciiPunctuation(c)

    /**
     * Unicode 标点：ASCII 标点或 Unicode 类别 P（标点）或 S（符号）。
     */
    fun isUnicodePunctuation(c: Char): Boolean {
        if (isAsciiPunctuation(c)) return true
        val category = c.category
        return category == CharCategory.DASH_PUNCTUATION ||
                category == CharCategory.START_PUNCTUATION ||
                category == CharCategory.END_PUNCTUATION ||
                category == CharCategory.CONNECTOR_PUNCTUATION ||
                category == CharCategory.OTHER_PUNCTUATION ||
                category == CharCategory.INITIAL_QUOTE_PUNCTUATION ||
                category == CharCategory.FINAL_QUOTE_PUNCTUATION ||
                category == CharCategory.MATH_SYMBOL ||
                category == CharCategory.CURRENCY_SYMBOL ||
                category == CharCategory.MODIFIER_SYMBOL ||
                category == CharCategory.OTHER_SYMBOL
    }

    /**
     * Unicode 空白：空格、制表符、换行符、换页符、回车符或 Unicode Zs 类别。
     */
    fun isUnicodeWhitespace(c: Char): Boolean {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r' ||
                c.category == CharCategory.SPACE_SEPARATOR
    }

    fun isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'

    fun isBlank(line: String): Boolean = line.all { isSpaceOrTab(it) }

    /**
     * 计算前导空格数（制表符按 4 空格制表位计算）。
     */
    fun countLeadingSpaces(line: String): Int {
        var spaces = 0
        for (c in line) {
            when (c) {
                ' ' -> spaces++
                '\t' -> spaces = ((spaces / 4) + 1) * 4
                else -> break
            }
        }
        return spaces
    }

    /**
     * 从行首移除最多 [n] 个空格，展开制表符。
     */
    fun removeLeadingSpaces(line: String, n: Int): String {
        var spaces = 0
        var i = 0
        while (i < line.length && spaces < n) {
            when (line[i]) {
                ' ' -> {
                    spaces++
                    i++
                }
                '\t' -> {
                    val tabWidth = 4 - (spaces % 4)
                    if (spaces + tabWidth > n) {
                        // 部分制表符：用剩余空格替换
                        val remaining = n - spaces
                        return " ".repeat(tabWidth - remaining) + line.substring(i + 1)
                    }
                    spaces += tabWidth
                    i++
                }
                else -> break
            }
        }
        return line.substring(i)
    }

    /**
     * 规范化链接标签：去除首尾空格，折叠内部空白，转为小写。
     */
    fun normalizeLinkLabel(label: String): String {
        return label.trim().replace(Regex("\\s+"), " ").lowercase()
    }

}