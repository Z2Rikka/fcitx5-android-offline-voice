/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.R
import java.io.File

/**
 * Which sherpa-onnx online model family a [VoiceModel] belongs to.
 *
 * [ZipformerTransducer] needs an encoder, a decoder and a joiner,
 * [Paraformer] only needs an encoder and a decoder.
 */
@Serializable
enum class VoiceModelBackend(val modelType: String) {
    ZipformerTransducer("zipformer"),
    Paraformer("paraformer");

    companion object {
        fun fromName(name: String?): VoiceModelBackend? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.modelType == name }
    }
}

/** File names of a model, relative to the model directory. */
@Serializable
data class VoiceModelFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String?,
    val tokens: String,
    val backend: VoiceModelBackend = VoiceModelBackend.ZipformerTransducer
) {
    /** File names that must exist for the model to be usable. */
    val required: List<String>
        get() = listOfNotNull(encoder, decoder, joiner, tokens)

    fun resolve(directory: String) = VoiceModelFiles(
        encoder = "$directory/$encoder",
        decoder = "$directory/$decoder",
        joiner = joiner?.let { "$directory/$it" },
        tokens = "$directory/$tokens",
        backend = backend
    )

    companion object {
        /** Layout used by the streaming zipformer models bundled with the APK. */
        fun zipformer(
            encoder: String = "encoder.int8.onnx",
            decoder: String = "decoder.onnx",
            joiner: String = "joiner.int8.onnx",
            tokens: String = "tokens.txt"
        ) = VoiceModelFiles(encoder, decoder, joiner, tokens, VoiceModelBackend.ZipformerTransducer)

        fun paraformer(
            encoder: String = "encoder.int8.onnx",
            decoder: String = "decoder.onnx",
            tokens: String = "tokens.txt"
        ) = VoiceModelFiles(encoder, decoder, null, tokens, VoiceModelBackend.Paraformer)
    }
}

/** Where the model files live. */
sealed interface VoiceModelSource {
    /** Read-only files shipped inside the APK, addressed relative to the asset root. */
    data class Assets(val directory: String) : VoiceModelSource

    /** Files installed into the app's private storage, removable by the user. */
    data class Files(val directory: File) : VoiceModelSource
}

data class VoiceModel(
    val id: String,
    val name: String,
    val note: String,
    val files: VoiceModelFiles,
    val source: VoiceModelSource
) {
    val removable: Boolean get() = source is VoiceModelSource.Files
}

/**
 * Models that ship inside the APK.
 *
 * Both entries use the same handful of canonical file names, so the CI script can rename
 * whatever the upstream repository publishes into this layout.
 */
object VoiceModels {

    const val DEFAULT_ID = "bilingual-zipformer"

    /** Directory under `assets/` that holds every bundled model. */
    const val ASSET_ROOT = "offline-voice"

    data class BuiltIn(
        val id: String,
        @StringRes val nameRes: Int,
        @StringRes val noteRes: Int,
        val directory: String,
        val files: VoiceModelFiles
    )

    val builtIn: List<BuiltIn> = listOf(
        BuiltIn(
            id = DEFAULT_ID,
            nameRes = R.string.offline_voice_model_bilingual_name,
            noteRes = R.string.offline_voice_model_bilingual_note,
            directory = ASSET_ROOT,
            files = VoiceModelFiles.zipformer()
        ),
        BuiltIn(
            id = "zh-14m-zipformer",
            nameRes = R.string.offline_voice_model_zh14m_name,
            noteRes = R.string.offline_voice_model_zh14m_note,
            directory = "$ASSET_ROOT/zh-14m",
            files = VoiceModelFiles.zipformer()
        )
    )
}
