/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.remote.CatalogEntry
import org.fcitx.fcitx5.android.data.remote.Downloader
import org.fcitx.fcitx5.android.data.remote.RemoteCatalog
import org.fcitx.fcitx5.android.input.voice.VoiceModel
import org.fcitx.fcitx5.android.input.voice.VoiceModelManager
import org.fcitx.fcitx5.android.input.voice.VoiceModelSource
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File

/**
 * Switch between bundled / imported / downloaded offline voice models, uninstall imported ones,
 * and pull optional models from the remote catalog.
 */
class VoiceModelFragment : PaddingPreferenceFragment() {

    private val manager by lazy { VoiceModelManager(requireContext()) }

    private val importZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    private fun buildScreen() {
        val ctx = requireContext()
        val models = manager.availableModels()
        val selected = manager.selectedModel()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.offline_voice_model_category) {
                if (models.isEmpty()) {
                    addPreference(
                        R.string.offline_voice_model_none,
                        R.string.offline_voice_model_none_summary
                    )
                }
                models.forEach { model ->
                    val current = model.id == selected?.id
                    val action: (() -> Unit)? = if (current) null else {
                        {
                            if (manager.select(model.id)) {
                                ctx.toast(
                                    ctx.getString(
                                        R.string.offline_voice_model_switched, model.name
                                    )
                                )
                            }
                            buildScreen()
                        }
                    }
                    addPreference(
                        title = if (current) "✓ ${model.name}" else model.name,
                        summary = summaryOf(model, current),
                        onClick = action
                    )
                }
            }
            addCategory(R.string.voice_model_download_category) {
                addPreference(R.string.catalog_refresh) { refreshCatalog() }
                val entries = RemoteCatalog.load(ctx)
                    .ofKind(RemoteCatalog.KIND_VOICE)
                    .filter { entry -> manager.availableModels().none { it.id == entry.id } }
                if (entries.isEmpty()) {
                    addPreference(
                        R.string.offline_voice_model_none,
                        R.string.offline_voice_model_none_summary
                    )
                }
                entries.forEach { entry ->
                    val row = Preference(ctx).apply {
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        title = entry.name
                        summary = downloadSummary(entry)
                        setOnPreferenceClickListener { clicked ->
                            startDownload(entry, clicked)
                            true
                        }
                    }
                    addPreference(row)
                }
            }
            addCategory(R.string.offline_voice_model_manage) {
                addPreference(R.string.offline_voice_model_import) {
                    importZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
                val used = manager.importedBytesUsed()
                addPreference(
                    R.string.offline_voice_model_storage,
                    VoiceModelManager.formatSize(used)
                )
                models.filter { it.removable }.forEach { model ->
                    addPreference(
                        title = ctx.getString(R.string.offline_voice_model_uninstall_name, model.name),
                        summary = VoiceModelManager.formatSize(sizeOf(model)),
                        onClick = { confirmUninstall(model) }
                    )
                }
            }
        }
    }

    private fun summaryOf(model: VoiceModel, current: Boolean): String {
        val ctx = requireContext()
        val parts = mutableListOf<String>()
        if (current) parts += ctx.getString(R.string.offline_voice_model_current)
        if (model.note.isNotBlank()) parts += model.note
        parts += if (model.removable) {
            VoiceModelManager.formatSize(sizeOf(model))
        } else {
            ctx.getString(R.string.offline_voice_model_bundled)
        }
        return parts.joinToString(SEPARATOR)
    }

    private fun sizeOf(model: VoiceModel): Long =
        (model.source as? VoiceModelSource.Files)?.directory
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun downloadSummary(entry: CatalogEntry): String {
        val parts = mutableListOf<String>()
        if (entry.note.isNotBlank()) parts += entry.note
        if (entry.sizeBytes > 0) {
            parts += getString(
                R.string.download_size, VoiceModelManager.formatSize(entry.sizeBytes)
            )
        }
        return parts.joinToString(SEPARATOR)
    }

    private fun startDownload(entry: CatalogEntry, row: Preference?) {
        val ctx = requireContext()
        if (downloadJob?.isActive == true) {
            ctx.toast(R.string.download_in_progress)
            return
        }
        val staging = manager.stagingDir(entry.id)
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            staging.deleteRecursively()
            staging.mkdirs()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    entry.files.forEachIndexed { index, file ->
                        Downloader.download(
                            url = file.url,
                            target = File(staging, file.name),
                            expectedSha256 = file.sha256,
                            expectedSize = file.size
                        ) { done, total ->
                            if (total <= 0) return@download
                            val percent = (done * 100 / total).toInt()
                            mainHandler.post {
                                row?.summary = ctx.getString(
                                    R.string.download_progress, percent
                                )
                            }
                        }.getOrThrow()
                        Timber.d("voice model %s: file %d/%d", entry.id, index + 1, entry.files.size)
                    }
                    manager.installDownloaded(
                        entry.id, entry.name, entry.note, entry.backend, staging
                    ).getOrThrow()
                }
            }
            result
                .onSuccess { model ->
                    manager.select(model.id)
                    ctx.toast(getString(R.string.download_done, model.name))
                    buildScreen()
                }
                .onFailure {
                    Timber.w(it, "download %s failed", entry.id)
                    staging.deleteRecursively()
                    ctx.toast(getString(R.string.download_failed, it.message ?: ""))
                    buildScreen()
                }
        }
    }

    private fun refreshCatalog() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteCatalog.refresh(ctx)
                .onSuccess { ctx.toast(getString(R.string.catalog_refresh_done, it.entries.size)) }
                .onFailure { ctx.toast(getString(R.string.catalog_refresh_failed, it.message ?: "")) }
            buildScreen()
        }
    }

    private fun confirmUninstall(model: VoiceModel) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.offline_voice_model_uninstall)
            .setMessage(getString(R.string.offline_voice_model_uninstall_confirm, model.name))
            .setPositiveButton(R.string.offline_voice_model_uninstall) { _, _ ->
                if (manager.uninstall(model.id)) {
                    requireContext().toast(R.string.offline_voice_model_uninstalled)
                }
                buildScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importModel(uri: Uri) {
        val ctx = requireContext()
        val name = displayName(uri)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { manager.importFromZip(uri, name).getOrThrow() }
            }
            result
                .onSuccess {
                    manager.select(it.id)
                    ctx.toast(getString(R.string.offline_voice_model_imported, it.name))
                    buildScreen()
                }
                .onFailure {
                    ctx.toast(getString(R.string.offline_voice_model_import_failed, it.message ?: ""))
                }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        requireContext().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    private companion object {
        const val SEPARATOR = " · "
    }
}
