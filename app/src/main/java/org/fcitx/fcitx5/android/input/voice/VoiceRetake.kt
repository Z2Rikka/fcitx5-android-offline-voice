/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

/**
 * Whether a previous dictation result can be taken back before re-dictating.
 *
 * Keeping this pure makes the guard testable: the text before the cursor must
 * still be exactly what was inserted, otherwise the user has edited it and a
 * retake would destroy their work.
 */
object VoiceRetake {
    fun canRetake(before: CharSequence?, expected: String): Boolean {
        if (expected.isEmpty() || before == null) return false
        if (before.length != expected.length) return false
        return before.toString() == expected
    }
}
