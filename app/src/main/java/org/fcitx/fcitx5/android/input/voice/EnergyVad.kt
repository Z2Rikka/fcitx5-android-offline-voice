/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Frame-energy voice activity detection used to end a dictation on silence.
 *
 * The noise floor adapts while nobody is speaking, so the speech threshold
 * follows a quiet room as well as a noisy street. Everything is integer
 * arithmetic on 16-bit frames, which keeps it cheap enough to run on the
 * recording thread next to the recognizer.
 */
class EnergyVad(
    private val sampleRate: Int = 16000,
    private val silenceMillisToEnd: Int = 900,
    private val minSpeechMillis: Int = 200,
    private val noSpeechTimeoutMillis: Int = 6000
) {
    enum class Decision { Continue, EndOfSpeech, NoSpeech }

    private var noiseFloor = 220.0
    private var speaking = false
    private var speechMillis = 0
    private var silenceMillis = 0
    private var elapsedMillis = 0

    fun reset() {
        noiseFloor = 220.0
        speaking = false
        speechMillis = 0
        silenceMillis = 0
        elapsedMillis = 0
    }

    fun accept(samples: ShortArray, count: Int): Decision {
        if (count <= 0) return Decision.Continue
        var sum = 0.0
        for (i in 0 until count) {
            val value = samples[i].toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / count)
        val frameMillis = count * 1000 / sampleRate
        elapsedMillis += frameMillis

        val threshold = max(noiseFloor * 3.0, 450.0)
        val voiced = rms > threshold && rms > noiseFloor * 1.8

        if (voiced) {
            speaking = true
            speechMillis += frameMillis
            silenceMillis = 0
        } else {
            // Track the room while idle so the threshold never drifts into speech level.
            noiseFloor = noiseFloor * 0.95 + rms * 0.05
            if (speaking) {
                silenceMillis += frameMillis
                if (silenceMillis >= silenceMillisToEnd && speechMillis >= minSpeechMillis) {
                    return Decision.EndOfSpeech
                }
            }
        }

        if (!speaking && elapsedMillis >= noSpeechTimeoutMillis) return Decision.NoSpeech
        return Decision.Continue
    }
}
