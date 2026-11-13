/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EnergyVadTest {

    private val frame = 320 // 20 ms at 16 kHz

    private fun tone(amplitude: Int): ShortArray =
        ShortArray(frame) { (amplitude * sin(2 * PI * 220 * it / 16000.0)).toInt().toShort() }

    private fun silence(): ShortArray = ShortArray(frame)

    private fun run(vad: EnergyVad, frames: List<ShortArray>): EnergyVad.Decision {
        var decision = EnergyVad.Decision.Continue
        for (buffer in frames) {
            decision = vad.accept(buffer, buffer.size)
            if (decision != EnergyVad.Decision.Continue) return decision
        }
        return decision
    }

    @Test
    fun endsAfterSpeechFollowedBySilence() {
        val vad = EnergyVad()
        val speech = List(25) { tone(6000) }
        val trailing = List(60) { silence() }
        assertEquals(EnergyVad.Decision.Continue, run(vad, speech))
        // 60 frames of 20 ms is 1.2 s, beyond the 900 ms silence window.
        assertEquals(EnergyVad.Decision.EndOfSpeech, run(vad, trailing))
    }

    @Test
    fun reportsNoSpeechInASilentRoom() {
        val vad = EnergyVad()
        assertEquals(EnergyVad.Decision.NoSpeech, run(vad, List(400) { silence() }))
    }

    @Test
    fun ignoresSilenceBeforeSpeech() {
        val vad = EnergyVad()
        assertEquals(EnergyVad.Decision.Continue, run(vad, List(50) { silence() }))
        assertEquals(EnergyVad.Decision.Continue, run(vad, List(25) { tone(6000) }))
    }

    @Test
    fun toleratesSteadyNoiseFloor() {
        val vad = EnergyVad()
        // A quiet room with a constant hiss must not be treated as speech.
        assertEquals(EnergyVad.Decision.NoSpeech, run(vad, List(400) { tone(120) }))
        // Real speech above that floor still ends normally.
        assertEquals(EnergyVad.Decision.Continue, run(vad, List(25) { tone(6000) }))
        assertEquals(EnergyVad.Decision.EndOfSpeech, run(vad, List(60) { tone(120) }))
    }
}
