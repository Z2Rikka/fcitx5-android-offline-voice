/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseNumberItnTest {

    private fun check(expected: String, input: String) =
        assertEquals(input, expected, ChineseNumberItn.normalize(input))

    @Test
    fun digitStrings() {
        check("2011年八月完工", "二零一一年八月完工")
        check("电话138", "电话一三八")
        check("编号007", "编号零零七")
    }

    @Test
    fun decimalsAndPercentages() {
        check("3.5", "三点五")
        check("50%是50%", "百分之五十是百分之五十")
        check("20.5%", "百分之二十点五")
        check("11.5", "十一点五")
    }

    @Test
    fun quantities() {
        check("15米", "十五米")
        check("105", "一百零五")
        check("一共15", "一共十五")
        // A decimal followed by 亿/万 is left alone; only plain decimals are rewritten.
        check("一点二亿", "一点二亿")
        check("120000000", "一亿二千万")
    }

    @Test
    fun keepsOrdinalsAndWeekdays() {
        check("第三点", "第三点")
        check("星期三", "星期三")
        check("周五", "周五")
    }

    @Test
    fun keepsNumericLookingWords() {
        check("一定会好", "一定会好")
        check("千万别忘记", "千万别忘记")
        check("万分之一", "万分之一")
        check("十分感谢", "十分感谢")
        check("一点小事", "一点小事")
        check("一一对应", "一一对应")
        check("两个苹果", "两个苹果")
    }

    @Test
    fun leavesPlainTextAlone() {
        check("我们明天见", "我们明天见")
        check("", "")
    }
}
