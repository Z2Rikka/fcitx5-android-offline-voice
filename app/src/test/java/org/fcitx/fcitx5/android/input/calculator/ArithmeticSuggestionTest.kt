/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArithmeticSuggestionTest {

    private fun suggest(text: String?) = ArithmeticSuggestion.fromTextBeforeCursor(text)

    @Test
    fun basicAddition() {
        val s = suggest("3+3")!!
        assertEquals("3+3", s.expression)
        assertEquals("6", s.result)
        assertEquals("3+3=6", s.text)
    }

    @Test
    fun keepsSurroundingText() {
        assertEquals("6", suggest("结果 3+3")!!.result)
        assertEquals("6", suggest("共3+3")!!.result)
        assertEquals("10", suggest("2*3+4")!!.result)
        assertEquals("4", suggest("10-2*3")!!.result)
    }

    @Test
    fun decimals() {
        assertEquals("0.3", suggest("0.1+0.2")!!.result)
        assertEquals("3", suggest("1.5*2")!!.result)
        assertEquals("2.5", suggest("5/2")!!.result)
    }

    @Test
    fun fullWidthOperators() {
        assertEquals("42", suggest("6×7")!!.result)
        assertEquals("3", suggest("9÷3")!!.result)
    }

    @Test
    fun negativeResult() {
        assertEquals("-9", suggest("9-9-9")!!.result)
    }

    @Test
    fun divisionByZero() {
        assertNull(suggest("1/0"))
    }

    @Test
    fun needsAnOperator() {
        assertNull(suggest("3"))
        assertNull(suggest(""))
        assertNull(suggest(null))
        assertNull(suggest("3+"))
    }

    @Test
    fun doesNotBreakWords() {
        assertNull(suggest("abc3+3"))
        assertNull(suggest("1.2.3+4"))
        assertNull(suggest("v3+3"))
    }

    @Test
    fun ignoresDatesAndTimes() {
        assertNull(suggest("2024-01-01"))
        assertNull(suggest("24-1-1"))
        assertNull(suggest("12:30"))
    }

    @Test
    fun fullWidthDigits() {
        assertEquals("6", suggest("３＋３")!!.result)
    }

    @Test
    fun tooLong() {
        assertNull(suggest("1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1"))
    }
}
