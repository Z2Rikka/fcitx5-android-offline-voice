/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.fcitx.fcitx5.android.R
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

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: OnlineRecognizer? = null // worker thread only
    private var session: Session? = null // main thread only
    private var generation = 0L
    private var destroyed = false
    private var statusKey: TextKeyView? = null
    private var originalLabel: CharSequence? = null

    /**
     * Open a recorder, degrading the audio source if the first choice is rejected.
     * Some vendor builds refuse VOICE_RECOGNITION or return a dead AudioRecord, in
     * which case MIC or DEFAULT still usually works.
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
        val current = Session(connection, generation)
        session = current
        showStatus(service.getString(R.string.offline_voice_preparing))
        worker.execute { recognize(current) }
        return true
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
        clearStatus()
    }

    fun editorChanged() {
        generation++
        cancel()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked by start(); revocation is caught below
    private fun recognize(current: Session) {
        var recorder: AudioRecord? = null
        var failure: Throwable? = null
        var result = ""
        try {
            val model = engine()
            if (current.cancelled.get() || current.released.get()) return
            val audio = openRecorder()
            recorder = audio
            checkNotNull(audio) { "Microphone unavailable" }
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
                val vad = EnergyVad()
                val started = SystemClock.elapsedRealtime()
                var countTotal = 0
                var readFailures = 0
                while (!current.cancelled.get() && !current.released.get()) {
                    check(SystemClock.elapsedRealtime() - started < 60000) { "Recording limit (60s)" }
                    val count = audio.read(samples, 0, samples.size)
                    if (count <= 0) {
                        // Transient read errors happen right after the stream opens.
                        check(++readFailures < 8) { "Microphone read failed: $count" }
                        Thread.sleep(20)
                        continue
                    }
                    readFailures = 0
                    countTotal += count
                    stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000)
                    while (model.isReady(stream) && !current.cancelled.get()) model.decode(stream)
                    if (vad.accept(samples, count) != EnergyVad.Decision.Continue) break
                }
                runCatching {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                }
                if (!current.cancelled.get() && countTotal >= 1600) {
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (model.isReady(stream) && !current.cancelled.get()) model.decode(stream)
                    if (!current.cancelled.get()) result = model.getResult(stream).text.trim()
                }
            } finally {
                stream.release()
            }
        } catch (error: Exception) {
            failure = error
            Timber.w(error, "Offline voice recognition failed")
        } finally {
            recorder?.let { audio ->
                runCatching { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
                audio.release()
            }
            main.post {
                if (session === current) {
                    session = null
                    clearStatus()
                    if (!destroyed && !current.cancelled.get() && generation == current.generation &&
                        service.currentInputConnection === current.connection && service.isInputViewShown) {
                        when {
                            failure != null -> notify(service.getString(R.string.offline_voice_failed))
                            result.isNotBlank() -> {
                                current.connection.finishComposingText()
                                service.commitText(ChineseNumberItn.normalize(result))
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
}
