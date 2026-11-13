/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

/**
 * Conservative inverse text normalization for dictated Chinese numbers.
 *
 * Rewritten patterns:
 * - digit-by-digit strings of two or more characters (`二零一一年` -> `2011年`)
 * - decimals (`三点五` -> `3.5`) and percentages (`百分之五十` -> `50%`)
 * - unit readings followed by a measure word (`十五米` -> `15米`), or ending a
 *   sentence with a value of at least ten (`一共十五` -> `一共15`)
 *
 * Everything else is left untouched: ordinals and weekdays (`第三`, `星期三`),
 * and common words that merely look numeric (`万一`, `千万`, `十分`).
 */
object ChineseNumberItn {

    private const val DIGITS = "零〇一二三四五六七八九幺"
    private const val UNITS = "十百千万亿"
    private const val POINT = '点'
    private val NUMERALS = (DIGITS + UNITS + POINT).toSet()

    private val DIGIT_VALUE = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '幺' to 1
    )

    /** Numeral-only words whose Chinese form must be preserved. */
    private val PROTECTED = setOf(
        "万一", "千万", "十分", "万分", "百般", "千般", "十万", "一一",
        "三番五次", "千方百计", "千真万确", "十万火急"
    )

    /** Numeral-plus-character words, checked against the character after the run. */
    private val SUFFIX_PROTECTED = setOf("十分", "百分", "万分", "百般", "千般", "万般")

    /** Characters that mark a preceding number as a quantity rather than a word. */
    private val MEASURE_WORDS = setOf(
        '年', '月', '日', '号', '时', '点', '分', '秒', '周', '天', '岁',
        '个', '只', '件', '次', '倍', '人', '位', '名', '元', '块', '毛', '角',
        '米', '里', '斤', '克', '吨', '升', '度', '页', '条', '张', '台', '套',
        '层', '楼', '级', '组', '项', '笔', '股', '章', '节', '段', '课'
    )

    private val ORDINAL_PREFIX = listOf("第", "星期", "礼拜", "周")

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            // `百分之X` must swallow the prefix, otherwise it becomes `百分之50%`.
            if (text.startsWith("百分之", index)) {
                var end = index + 3
                while (end < text.length && text[end] in NUMERALS) end++
                val run = text.substring(index + 3, end)
                val value = if (run in PROTECTED) null else parseNumber(run)
                if (value != null) {
                    out.append(value).append('%')
                    index = end
                    continue
                }
            }
            if (text[index] !in NUMERALS) {
                out.append(text[index])
                index++
                continue
            }
            var end = index
            while (end < text.length && text[end] in NUMERALS) end++
            out.append(rewrite(text, index, end, text.substring(index, end)))
            index = end
        }
        return out.toString()
    }

    private fun rewrite(text: String, start: Int, end: Int, run: String): String {
        if (run in PROTECTED) return run
        if (ORDINAL_PREFIX.any { start >= it.length && text.regionMatches(start - it.length, it, 0, it.length) }) {
            return run
        }
        // 十分/百分/万般... are words, not quantities.
        if (end < text.length && text.substring(start, end + 1) in SUFFIX_PROTECTED) return run
        val next = text.getOrNull(end)
        if (POINT in run) return parseNumber(run) ?: run
        if (run.none { it in UNITS }) {
            // Digit-by-digit readings keep leading zeros: 零零七 -> 007.
            if (run.length < 2) return run
            return run.map { DIGIT_VALUE[it] ?: return run }.joinToString("")
        }
        val value = parseInteger(run) ?: return run
        if (next != null && next in MEASURE_WORDS) return value.toString()
        if (next == null && run.length >= 2 && value >= 10L) return value.toString()
        return run
    }

    /** Accepts an optional single decimal point, e.g. `三点五`. */
    private fun parseNumber(run: String): String? {
        val parts = run.split(POINT)
        if (parts.size == 1) return parseInteger(run)?.toString()
        if (parts.size != 2) return null
        val whole = parseInteger(parts[0]) ?: return null
        // `一点`/`有点儿` mean "a little"; only multi-character wholes are quantities.
        if (parts[1].isEmpty()) return if (parts[0].length >= 2) whole.toString() else null
        val fraction = parts[1].map { DIGIT_VALUE[it] ?: return null }.joinToString("")
        return "$whole.$fraction"
    }

    private fun parseInteger(run: String): Long? {
        if (run.isEmpty() || run.any { it !in NUMERALS } || run.any { it == POINT }) return null
        if (run.none { it in UNITS }) {
            val digits = run.map { DIGIT_VALUE[it] ?: return null }
            return digits.fold(0L) { acc, d -> acc * 10 + d }
        }
        var total = 0L
        var section = 0L
        var number = 0L
        for (c in run) {
            when (c) {
                in DIGITS -> number = (DIGIT_VALUE[c] ?: return null).toLong()
                '十', '百', '千' -> {
                    val unit = when (c) {
                        '十' -> 10L
                        '百' -> 100L
                        else -> 1000L
                    }
                    section += (if (number == 0L) 1L else number) * unit
                    number = 0L
                }
                '万' -> {
                    section += number
                    if (section == 0L) return null
                    total += section * 10_000L
                    section = 0L
                    number = 0L
                }
                '亿' -> {
                    section += number
                    if (section == 0L) return null
                    total = (total + section) * 100_000_000L
                    section = 0L
                    number = 0L
                }
            }
        }
        val value = total + section + number
        return if (value > 0L) value else null
    }
}
