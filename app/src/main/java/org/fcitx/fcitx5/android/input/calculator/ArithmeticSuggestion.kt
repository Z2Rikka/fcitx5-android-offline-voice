/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.calculator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Detects a trailing arithmetic expression in the text before the cursor, so the candidate bar
 * can offer `3+3=6`. Nothing is inserted unless that candidate is tapped.
 *
 * Only `+ - * / × ÷` over decimal numbers is supported (no parentheses). Evaluation uses
 * [BigDecimal], so `0.1+0.2` gives `0.3` instead of `0.30000000000000004`.
 */
object ArithmeticSuggestion {

    /** Marks a synthetic candidate as "this is the calculator chip", not an engine candidate. */
    const val LABEL = "\u0001calc"

    /** How much text before the cursor we ask the editor for. */
    const val MAX_LOOKBEHIND = 32

    /** Longest expression we are willing to show / evaluate. */
    private const val MAX_INPUT_LENGTH = 24

    private const val MAX_TERMS = 6
    private const val MAX_RESULT_LENGTH = 20
    private const val DIVISION_SCALE = 10

    /** Half-width operators after [normalize]. */
    private const val OPERATORS = "+-*/"

    /** Full-width / typographic characters we accept as input. */
    private const val FULL_WIDTH = "０１２３４５６７８９＋－＊／．×÷"

    data class Suggestion(val expression: String, val result: String) {
        /** Text that replaces the typed expression when the candidate is chosen. */
        val text: String get() = "$expression=$result"
    }

    /**
     * @param textBeforeCursor result of `InputConnection.getTextBeforeCursor()`
     */
    fun fromTextBeforeCursor(textBeforeCursor: String?): Suggestion? {
        if (textBeforeCursor.isNullOrEmpty()) return null
        var start = textBeforeCursor.length
        while (start > 0 && isExpressionChar(textBeforeCursor[start - 1])) start--
        if (start == textBeforeCursor.length) return null
        val expression = textBeforeCursor.substring(start)
        if (expression.length > MAX_INPUT_LENGTH) return null
        // must not continue a latin word or number to its left ("abc3+3", "1.2.3+4"),
        // while CJK text directly before it is a normal boundary ("共3+3")
        if (start > 0 && isLatinWordChar(textBeforeCursor[start - 1])) return null
        val normalized = normalize(expression)
        if (normalized.length < 3) return null
        if (normalized[0] !in '0'..'9') return null
        if (normalized.last() in OPERATORS) return null
        if (normalized.none { it in OPERATORS }) return null
        // "2024-01-01" and friends are dates, not arithmetic
        if (looksLikeDate(normalized)) return null
        val result = evaluate(normalized) ?: return null
        return Suggestion(expression, result)
    }

    /**
     * @return the formatted result, or `null` when the expression is not something we accept
     */
    internal fun evaluate(expression: String): String? {
        if (expression.isEmpty()) return null
        val numbers = ArrayList<BigDecimal>(MAX_TERMS)
        val operators = StringBuilder()
        var i = 0
        while (i < expression.length) {
            val start = i
            while (i < expression.length &&
                (expression[i].isDigit() || expression[i] == '.')
            ) i++
            if (i == start) return null
            val number = expression.substring(start, i).toBigDecimalOrNull() ?: return null
            if (numbers.size >= MAX_TERMS) return null
            numbers.add(number)
            if (i == expression.length) break
            val operator = expression[i]
            if (operator !in OPERATORS) return null
            operators.append(operator)
            i++
        }
        if (numbers.size < 2 || operators.length != numbers.size - 1) return null

        // multiplication and division first, then addition and subtraction
        var index = 0
        while (index < operators.length) {
            val operator = operators[index]
            if (operator == '*' || operator == '/') {
                val left = numbers[index]
                val right = numbers[index + 1]
                val value = if (operator == '*') {
                    left.multiply(right)
                } else {
                    if (right.signum() == 0) return null
                    left.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP)
                }
                numbers[index] = value
                numbers.removeAt(index + 1)
                operators.deleteCharAt(index)
            } else {
                index++
            }
        }
        var acc = numbers[0]
        for (k in 0 until operators.length) {
            acc = if (operators[k] == '+') acc.add(numbers[k + 1])
            else acc.subtract(numbers[k + 1])
        }
        val plain = acc.stripTrailingZeros().toPlainString()
        if (plain.length > MAX_RESULT_LENGTH) return null
        return plain
    }

    private fun isExpressionChar(c: Char) =
        c.isDigit() || c == '.' || c in "+-*/" || c in FULL_WIDTH

    private fun isLatinWordChar(c: Char) =
        c in 'a'..'z' || c in 'A'..'Z' || c.isDigit() || c == '.' || c == '_'

    private fun normalize(text: String) = buildString(text.length) {
        for (c in text) {
            when (c) {
                in '０'..'９' -> append('0' + (c - '０'))
                '＋' -> append('+')
                '－' -> append('-')
                '＊' -> append('*')
                '／' -> append('/')
                '．' -> append('.')
                '×' -> append('*')
                '÷' -> append('/')
                else -> append(c)
            }
        }
    }

    /** `2024-01-01`, `24-1-1` are dates; plain subtraction chains like `9-9-9` still work. */
    private fun looksLikeDate(expression: String): Boolean {
        val parts = expression.split('-')
        if (parts.size != 3) return false
        if (parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return false
        if (parts[0].length !in 2..4 || parts[1].length > 2 || parts[2].length > 2) return false
        val month = parts[1].toIntOrNull() ?: return false
        val day = parts[2].toIntOrNull() ?: return false
        return month in 1..12 && day in 1..31
    }
}
