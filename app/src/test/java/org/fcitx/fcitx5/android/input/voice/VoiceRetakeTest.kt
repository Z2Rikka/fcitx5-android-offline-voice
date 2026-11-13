/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRetakeTest {

    @Test
    fun allowsRetakeWhenTextBeforeCursorIsUntouched() {
        assertTrue(VoiceRetake.canRetake("今天下午三点开会", "今天下午三点开会"))
    }

    @Test
    fun rejectsWhenUserEditedTheText() {
        assertFalse(VoiceRetake.canRetake("今天下午三点开会", "今天下午三点"))
        assertFalse(VoiceRetake.canRetake("今天是晴天", "今天下午三点开会"))
        assertFalse(VoiceRetake.canRetake("", "今天下午三点开会"))
    }

    @Test
    fun rejectsWhenNothingWasInserted() {
        assertFalse(VoiceRetake.canRetake("一些内容", ""))
        assertFalse(VoiceRetake.canRetake(null, "一些内容"))
    }

    @Test
    fun rejectsPartialOverlap() {
        // A shorter common suffix must not be treated as an untouched insertion.
        assertFalse(VoiceRetake.canRetake("开会", "今天下午三点开会"))
    }
}
