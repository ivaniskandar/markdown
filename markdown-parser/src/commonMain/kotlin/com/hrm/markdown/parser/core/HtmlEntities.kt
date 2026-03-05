package com.hrm.markdown.parser.core

/**
 * 常用 HTML 实体解析。
 * 覆盖最常用的命名实体以及数字（十进制/十六进制）实体。
 */
object HtmlEntities {

    private val namedEntities: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
        "laquo" to "\u00AB", "raquo" to "\u00BB",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "bull" to "\u2022", "middot" to "\u00B7",
        "larr" to "\u2190", "rarr" to "\u2192", "uarr" to "\u2191", "darr" to "\u2193",
        "harr" to "\u2194",
        "lsaquo" to "\u2039", "rsaquo" to "\u203A",
        "euro" to "\u20AC", "pound" to "\u00A3", "yen" to "\u00A5", "cent" to "\u00A2",
        "deg" to "\u00B0", "micro" to "\u00B5", "para" to "\u00B6", "sect" to "\u00A7",
        "times" to "\u00D7", "divide" to "\u00F7", "plusmn" to "\u00B1",
        "frac12" to "\u00BD", "frac14" to "\u00BC", "frac34" to "\u00BE",
        "iexcl" to "\u00A1", "iquest" to "\u00BF",
        "alpha" to "\u03B1", "beta" to "\u03B2", "gamma" to "\u03B3", "delta" to "\u03B4",
        "epsilon" to "\u03B5", "pi" to "\u03C0", "sigma" to "\u03C3", "omega" to "\u03C9",
        "infin" to "\u221E", "sum" to "\u2211", "prod" to "\u220F",
        "radic" to "\u221A", "ne" to "\u2260", "le" to "\u2264", "ge" to "\u2265",
        "hearts" to "\u2665", "diams" to "\u2666", "clubs" to "\u2663", "spades" to "\u2660",
        "check" to "\u2713", "cross" to "\u2717",
        "shy" to "\u00AD", "zwj" to "\u200D", "zwnj" to "\u200C",
    )

    private val entityRegex = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

    /**
     * 解析单个实体字符串，如 `&amp;`、`&#123;` 或 `&#x1F4A9;`。
     * 如果实体未被识别则返回 null。
     */
    fun resolve(entity: String): String? {
        val match = entityRegex.matchEntire(entity) ?: return null
        val inner = match.groupValues[1]
        return when {
            inner.startsWith("#x") || inner.startsWith("#X") -> {
                val codePoint = inner.substring(2).toIntOrNull(16) ?: return null
                if (codePoint == 0) return "\uFFFD"
                codePointToString(codePoint)
            }
            inner.startsWith("#") -> {
                val codePoint = inner.substring(1).toIntOrNull(10) ?: return null
                if (codePoint == 0) return "\uFFFD"
                codePointToString(codePoint)
            }
            else -> namedEntities[inner]
        }
    }

    /**
     * 将字符串中所有 HTML 实体替换为其解析后的值。
     */
    fun replaceAll(text: String): String {
        return entityRegex.replace(text) { match ->
            resolve(match.value) ?: match.value
        }
    }

    /**
     * 检查字符串在指定位置是否以有效的 HTML 实体开头。
     */
    fun matchAt(text: String, pos: Int): MatchResult? {
        if (pos >= text.length || text[pos] != '&') return null
        val sub = text.substring(pos, minOf(pos + 32, text.length))
        return entityRegex.find(sub)?.let { if (it.range.first == 0) it else null }
    }

    private fun codePointToString(codePoint: Int): String? {
        return try {
            if (codePoint in 0xD800..0xDFFF || codePoint > 0x10FFFF) return null
            if (codePoint <= 0xFFFF) {
                codePoint.toChar().toString()
            } else {
                val high = ((codePoint - 0x10000) shr 10) + 0xD800
                val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
                "${high.toChar()}${low.toChar()}"
            }
        } catch (_: Exception) {
            null
        }
    }
}
