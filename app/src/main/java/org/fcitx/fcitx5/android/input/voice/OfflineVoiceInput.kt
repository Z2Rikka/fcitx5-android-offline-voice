/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All model and microphone operations are serialized off the UI thread. No audio is saved. */
class OfflineVoiceInput(private val service: FcitxInputMethodService) {

    private class Session(val connection: InputConnection, val generation: Long) {
        val released = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
    }

    /** Text this class inserted most recently, so a retake can remove it. */
    private class LastCommit(val connection: InputConnection, val text: String)

    private enum class Failure(@StringRes val message: Int) {
        Microphone(R.string.offline_voice_error_microphone),
        Model(R.string.offline_voice_error_model),
        Recognition(R.string.offline_voice_error_recognition)
    }

    private class VoiceFailure(val failure: Failure, cause: Throwable? = null) :
        Exception(cause)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: OnlineRecognizer? = null // worker thread only
    private var session: Session? = null // main thread only
    private var lastCommit: LastCommit? = null // main thread only
    private var generation = 0L
    private var destroyed = false
    private var statusKey: TextKeyView? = null
    private var originalLabel: CharSequence? = null

    private fun engine(): OnlineRecognizer {
        recognizer?.let { return it }
        val directory = "offline-voice"
        return OnlineRecognizer(service.assets, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$directory/encoder.int8.onnx",
                    decoder = "$directory/decoder.onnx",
                    joiner = "$directory/joiner.int8.onnx"
                ),
                tokens = "$directory/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer"
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search"
        )).also { recognizer = it }
    }

    /**
     * Open a recorder, degrading the audio source if the first choice is rejected.
     * Some vendor builds refuse VOICE_RECOGNITION or hand back a dead AudioRecord,
     * in which case MIC or DEFAULT still usually works.
     */
    @android.annotation.SuppressLint("MissingPermission") // checked by start()
    private fun openRecorder(): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minimum > 0) maxOf(minimum * 4, 32000) else 32000
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
            }.getOrNull()
            if (recorder == null) {
                Timber.w("AudioRecord rejected for source %d", source)
                continue
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Timber.w("AudioRecord uninitialized for source %d", source)
                recorder.release()
                continue
            }
            val started = runCatching {
                recorder.startRecording()
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (started) {
                Timber.d("Recording with audio source %d", source)
                return recorder
            }
            Timber.w("AudioRecord failed to start for source %d", source)
            runCatching { recorder.release() }
        }
        return null
    }

    fun prepare() {
        if (destroyed) return
        worker.execute {
            runCatching { engine() }.onFailure { Timber.w(it, "Offline voice model unavailable") }
        }
    }

    fun start(): Boolean {
        if (destroyed || session != null) return false
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            service.startActivity(Intent(service, MicrophonePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val connection = service.currentInputConnection ?: return false
        val inputType = service.currentInputEditorInfo?.inputType ?: return false
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        if ((inputClass == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == android.text.InputType.TYPE_CLASS_NUMBER &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            notify(service.getString(R.string.offline_voice_password))
            return false
        }
        discardPreviousResult()
        val current = Session(connection, generation)
        session = current
        showStatus(service.getString(R.string.offline_voice_preparing))
        worker.execute { recognize(current) }
        return true
    }

    /** Long pressing again right after a dictation replaces it instead of appending. */
    private fun discardPreviousResult() {
        val last = lastCommit ?: return
        lastCommit = null
        val connection = service.currentInputConnection
        if (connection == null || connection !== last.connection) return
        val selection = service.currentInputSelection
        if (selection.start != selection.end) return
        val before = runCatching {
            connection.getTextBeforeCursor(last.text.length, 0)?.toString()
        }.getOrNull()
        if (!VoiceRetake.canRetake(before, last.text)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.deleteSurroundingTextInCodePoints(last.text.length, 0)
            } else {
                connection.deleteSurroundingText(last.text.length, 0)
            }
            Timber.d("Offline voice: removed previous result for retake")
        }
    }

    fun finish() {
        session?.let {
            it.released.set(true)
            showStatus(service.getString(R.string.offline_voice_processing))
        }
    }

    fun cancel() {
        session?.cancelled?.set(true)
        session = null
        lastCommit = null
        clearStatus()
    }

    fun editorChanged() {
        generation++
        cancel()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked by start(); revocation is caught below
    private fun recognize(current: Session) {
        var recorder: AudioRecord? = null
        var failure: Failure? = null
        var result = ""
        var samplesRead = 0
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val model = try {
                engine()
            } catch (error: Throwable) {
                throw VoiceFailure(Failure.Model, error)
            }
            if (current.cancelled.get() || current.released.get()) return
            val audio = openRecorder() ?: throw VoiceFailure(Failure.Microphone)
            recorder = audio
            val silenceTimeout =
                AppPrefs.getInstance().keyboard.voiceSilenceTimeout.getValue()
            val stream = model.createStream()
            try {
                main.post {
                    if (session === current && !current.released.get()) {
                        showStatus(service.getString(R.string.offline_voice_listening))
                        service.window.window?.decorView?.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                val samples = ShortArray(320)
                val vad = EnergyVad(silenceMillisToEnd = silenceTimeout)
                var readFailures = 0
                while (!current.cancelled.get() && !current.released.get()) {
                    // The 60 s cap ends the utterance normally instead of failing it.
                    if (SystemClock.elapsedRealtime() - startedAt >= MAX_RECORD_MILLIS) break
                    val count = audio.read(samples, 0, samples.size)
                    if (count <= 0) {
                        // Transient read errors happen right after the stream opens.
                        if (++readFailures >= 8) throw VoiceFailure(Failure.Microphone)
                        Thread.sleep(20)
                        continue
                    }
                    readFailures = 0
                    samplesRead += count
                    stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000)
                    while (model.isReady(stream) && !current.cancelled.get()) model.decode(stream)
                    if (vad.accept(samples, count) != EnergyVad.Decision.Continue) break
                }
                runCatching {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                }
                if (!current.cancelled.get() && samplesRead >= 1600) {
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (model.isReady(stream) && !current.cancelled.get()) model.decode(stream)
                    if (!current.cancelled.get()) result = model.getResult(stream).text.trim()
                }
            } finally {
                stream.release()
            }
        } catch (error: VoiceFailure) {
            failure = error.failure
            Timber.w(error, "Offline voice failed: ${error.failure}")
        } catch (error: Throwable) {
            failure = Failure.Recognition
            Timber.w(error, "Offline voice recognition failed")
        } finally {
            recorder?.let { audio ->
                runCatching {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                }
                audio.release()
            }
            Timber.d(
                "Offline voice: %d ms, %d samples, failure=%s, text=%d chars",
                SystemClock.elapsedRealtime() - startedAt, samplesRead, failure, result.length
            )
            main.post {
                if (session === current) {
                    session = null
                    clearStatus()
                    if (!destroyed && !current.cancelled.get() && generation == current.generation &&
                        service.currentInputConnection === current.connection && service.isInputViewShown) {
                        when {
                            failure != null -> notify(service.getString(failure.message))
                            result.isNotBlank() -> {
                                val text = ChineseNumberItn.normalize(result)
                                current.connection.finishComposingText()
                                service.commitText(text)
                                lastCommit = LastCommit(current.connection, text)
                            }
                            else -> notify(service.getString(R.string.offline_voice_empty))
                        }
                    }
                }
            }
        }
    }

    private fun showStatus(label: String) {
        if (statusKey == null) {
            statusKey = service.window.window?.decorView?.findViewById(R.id.button_space)
            originalLabel = statusKey?.mainText?.text
        }
        statusKey?.mainText?.text = label
    }

    private fun clearStatus() {
        originalLabel?.let { statusKey?.mainText?.text = it }
        statusKey = null
        originalLabel = null
    }

    private fun notify(message: String) = Toast.makeText(service, message, Toast.LENGTH_LONG).show()

    fun close() {
        destroyed = true
        editorChanged()
        worker.execute {
            recognizer?.release()
            recognizer = null
        }
        worker.shutdown()
    }

    private companion object {
        const val MAX_RECORD_MILLIS = 60_000L
    }
}
