/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Owns the voice models this app can use.
 *
 * Two kinds exist:
 *  - **bundled** models, read directly from `assets/offline-voice/...`. They cost no extra
 *    storage but cannot be uninstalled (deleting assets would need a new APK).
 *  - **imported** models, unzipped into `filesDir/voice-models/<id>/`. These can be uninstalled
 *    and are the way to add a model that is not part of the APK.
 *
 * The selected model id is stored in [AppPrefs.keyboard].`offlineVoiceModel`.
 */
class VoiceModelManager(private val context: Context) {

    private val prefs get() = AppPrefs.getInstance().keyboard

    private val root: File
        get() = File(context.filesDir, ROOT_DIR)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Bundled models whose assets are actually present plus every imported model. */
    fun availableModels(): List<VoiceModel> = bundledModels() + importedModels()

    fun bundledModels(): List<VoiceModel> = VoiceModels.builtIn.mapNotNull { builtIn ->
        if (!assetsPresent(builtIn.directory, builtIn.files)) return@mapNotNull null
        VoiceModel(
            id = builtIn.id,
            name = context.getString(builtIn.nameRes),
            note = context.getString(builtIn.noteRes),
            files = builtIn.files,
            source = VoiceModelSource.Assets(builtIn.directory)
        )
    }

    fun importedModels(): List<VoiceModel> {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?: return emptyList()
        return dirs.sortedBy { it.name }.mapNotNull { dir ->
            runCatching { readInstalled(dir) }
                .onFailure { Timber.w(it, "Ignoring broken voice model %s", dir.name) }
                .getOrNull()
        }
    }

    /**
     * The model used for the next utterance. Falls back to the first available model when the
     * stored selection was uninstalled or never existed.
     */
    fun selectedModel(): VoiceModel? {
        val models = availableModels()
        if (models.isEmpty()) return null
        val id = prefs.offlineVoiceModel.getValue()
        return models.firstOrNull { it.id == id } ?: models.first()
    }

    fun select(id: String): Boolean {
        if (availableModels().none { it.id == id }) return false
        prefs.offlineVoiceModel.setValue(id)
        return true
    }

    /** Delete an imported model. Bundled models cannot be removed. */
    fun uninstall(id: String): Boolean {
        val model = importedModels().firstOrNull { it.id == id } ?: return false
        val dir = (model.source as? VoiceModelSource.Files)?.directory ?: return false
        val deleted = dir.deleteRecursively()
        if (deleted) {
            if (prefs.offlineVoiceModel.getValue() == id) {
                prefs.offlineVoiceModel.setValue(VoiceModels.DEFAULT_ID)
            }
            Timber.i("Uninstalled voice model %s", id)
        }
        return deleted
    }

    /** Bytes occupied by imported models only; bundled ones live in the APK. */
    fun importedBytesUsed(): Long = importedModels().sumOf { model ->
        val dir = (model.source as? VoiceModelSource.Files)?.directory
        if (dir == null) 0L else dir.sizeRecursively()
    }

    /**
     * Install a model from a `.zip` picked by the user. The archive may flatten to a single
     * top level directory; only the file names matter.
     */
    fun importFromZip(uri: Uri, fallbackName: String?): Result<VoiceModel> = runCatching {
        val staging = File(root, ".import-${System.currentTimeMillis()}")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "Cannot create ${staging.name}" }
        try {
            val detected = extract(uri, staging)
            val meta = detected.meta
            val backend = VoiceModelBackend.fromName(meta?.type) ?: detected.backend
            val id = uniqueId(meta?.id ?: fallbackName ?: "imported-model")
            val files = detected.files.copy(backend = backend)
            val name = meta?.name?.takeIf { it.isNotBlank() }
                ?: fallbackName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: id
            val note = meta?.note.orEmpty()
            val target = File(root, id)
            check(!target.exists() || target.deleteRecursively()) { "Cannot replace $id" }
            check(staging.renameTo(target)) { "Cannot install $id" }
            val installed = InstalledModelMeta(id, name, note, files)
            File(target, META_FILE).writeText(json.encodeToString(installed))
            Timber.i("Imported voice model %s (%s)", id, backend)
            readInstalled(target)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun readInstalled(dir: File): VoiceModel {
        val metaFile = File(dir, META_FILE)
        val meta = json.decodeFromString<InstalledModelMeta>(metaFile.readText())
        val files = meta.files
        meta.files.required.forEach {
            check(File(dir, it).isFile) { "Missing $it in ${dir.name}" }
        }
        return VoiceModel(
            id = meta.id,
            name = meta.name.ifBlank { meta.id },
            note = meta.note,
            files = files,
            source = VoiceModelSource.Files(dir)
        )
    }

    private fun assetsPresent(directory: String, files: VoiceModelFiles): Boolean = runCatching {
        val top = directory.substringBefore('/')
        val nested = directory.substringAfter('/', "")
        val names = context.assets.list(if (nested.isEmpty()) top else "$top/$nested")
            ?.toSet().orEmpty()
        files.required.all { it in names }
    }.getOrDefault(false)

    private fun uniqueId(raw: String): String {
        val base = raw.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifEmpty { "imported-model" }
            .take(48)
        var id = base
        var n = 2
        while (File(root, id).exists()) {
            id = "$base-$n"
            n++
        }
        return id
    }

    private class Detected(
        val files: VoiceModelFiles,
        val backend: VoiceModelBackend,
        val meta: ZipMeta?
    )

    private fun extract(uri: Uri, target: File): Detected {
        var meta: ZipMeta? = null
        var total = 0L
        var count = 0
        val stored = mutableListOf<String>()
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open the selected file")
        stream.use {
            ZipInputStream(it.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name.isEmpty() ||
                        entry.name.startsWith("__MACOSX") || name.startsWith(".")
                    ) {
                        zip.closeEntry()
                        continue
                    }
                    if (++count > MAX_ENTRIES) error("Too many files in the archive")
                    total += entry.size.coerceAtLeast(0)
                    if (total > MAX_BYTES) error("The archive is too large")
                    when {
                        name == META_FILE -> meta = json.decodeFromString<ZipMeta>(
                            zip.readBytes().decodeToString()
                        )

                        name.endsWith(".onnx") || name.endsWith(".txt") -> {
                            File(target, name).outputStream().use { out -> zip.copyTo(out) }
                            stored += name
                        }

                        else -> Unit // ignore anything else, e.g. README or license files
                    }
                    zip.closeEntry()
                }
            }
        }
        check(stored.isNotEmpty()) { "The archive contains no model files" }
        fun pick(prefix: String, suffix: String): String? =
            // prefer the exact canonical name, then anything like encoder-epoch-99...onnx
            stored.firstOrNull { it == prefix + suffix }
                ?: stored.firstOrNull { it.startsWith(prefix) && it.endsWith(suffix) }
        val encoder = pick("encoder", ".onnx") ?: error("Missing encoder.onnx")
        val decoder = pick("decoder", ".onnx") ?: error("Missing decoder.onnx")
        val joiner = pick("joiner", ".onnx")
        val tokens = pick("tokens", ".txt") ?: error("Missing tokens.txt")
        val backend = if (joiner != null) VoiceModelBackend.ZipformerTransducer
        else VoiceModelBackend.Paraformer
        return Detected(
            files = VoiceModelFiles(encoder, decoder, joiner, tokens, backend),
            backend = backend,
            meta = meta
        )
    }

    companion object {
        private const val ROOT_DIR = "voice-models"
        private const val META_FILE = "model.json"
        private const val MAX_ENTRIES = 64
        private const val MAX_BYTES = 1_500_000_000L

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "-"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                val gb = (mb / 1024 * 10).roundToInt() / 10.0
                "$gb GB"
            } else {
                "${mb.roundToInt().coerceAtLeast(1)} MB"
            }
        }
    }
}

/** Metadata of an imported model, written next to its files. */
@Serializable
private data class InstalledModelMeta(
    val id: String,
    val name: String,
    val note: String = "",
    val files: VoiceModelFiles
)

/** Optional `model.json` that can be bundled inside an imported archive. */
@Serializable
private data class ZipMeta(
    val id: String? = null,
    val name: String? = null,
    val note: String? = null,
    val type: String? = null
)

private fun File.sizeRecursively(): Long =
    walkTopDown().filter { it.isFile }.sumOf { it.length() }
