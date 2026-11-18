/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.net.Uri
import android.os.Bundle
import android.view.View
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
import org.fcitx.fcitx5.android.data.remote.DownloadManager
import org.fcitx.fcitx5.android.data.remote.RemoteCatalog
import org.fcitx.fcitx5.android.input.voice.VoiceModel
import org.fcitx.fcitx5.android.input.voice.VoiceModelManager
import org.fcitx.fcitx5.android.input.voice.VoiceModelSource
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

/**
 * Switch between bundled / imported / downloaded offline voice models, uninstall imported ones,
 * and pull optional models from the remote catalog.
 *
 * Downloads are owned by [DownloadManager] (application scope), so leaving this screen keeps them
 * running; the rows simply observe their progress and can cancel them.
 */
class VoiceModelFragment : PaddingPreferenceFragment() {

    private val manager by lazy { VoiceModelManager(requireContext()) }

    private val importZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    /** Download rows of the current screen, keyed by catalog id. */
    private val downloadRows = HashMap<String, Preference>()
    private var observeJob: Job? = null
    private val announced = HashSet<String>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // `viewLifecycleOwner` is only valid from here on
        observeDownloads()
    }

    private fun buildScreen() {
        val ctx = requireContext()
        val models = manager.availableModels()
        val selected = manager.selectedModel()
        downloadRows.clear()
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
                    .filter { entry -> models.none { it.id == entry.id } }
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
                        setOnPreferenceClickListener {
                            toggleDownload(entry)
                            true
                        }
                    }
                    downloadRows[entry.id] = row
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
            parts += getString(R.string.download_size, VoiceModelManager.formatSize(entry.sizeBytes))
        }
        progressSuffix(DownloadManager.progressOf(entry.id))?.let { parts += it }
        return parts.joinToString(SEPARATOR)
    }

    private fun progressSuffix(progress: DownloadManager.Progress?): String? {
        if (progress == null) return null
        val text = when (progress.phase) {
            DownloadManager.Phase.Running -> {
                val percent = progress.percent
                val base = if (percent >= 0) {
                    getString(R.string.download_progress, percent)
                } else {
                    getString(R.string.download_progress_unknown)
                }
                if (progress.fileCount > 1) {
                    "$base (${progress.fileIndex}/${progress.fileCount})"
                } else {
                    base
                }
            }
            DownloadManager.Phase.Done -> getString(R.string.download_installed)
            DownloadManager.Phase.Failed -> getString(R.string.download_failed, progress.message)
            DownloadManager.Phase.Cancelled -> getString(R.string.download_cancelled)
        }
        val hint = when (progress.phase) {
            DownloadManager.Phase.Running -> getString(R.string.download_cancel_hint)
            DownloadManager.Phase.Failed,
            DownloadManager.Phase.Cancelled -> getString(R.string.download_retry_hint)
            else -> null
        }
        return if (hint != null) "$text$SEPARATOR$hint" else text
    }

    /** Click on a download row: start it, or cancel it when it is running. */
    private fun toggleDownload(entry: CatalogEntry) {
        val ctx = requireContext()
        if (DownloadManager.isRunning(entry.id)) {
            DownloadManager.cancel(entry.id)
            ctx.toast(R.string.download_cancelled)
            return
        }
        DownloadManager.clear(entry.id)
        val started = DownloadManager.start(
            DownloadManager.Task(
                id = entry.id,
                staging = VoiceModelManager(appContext).stagingDir(entry.id),
                files = entry.files
            ) { staging ->
                val models = VoiceModelManager(appContext)
                val model = models.installDownloaded(
                    entry.id, entry.name, entry.note, entry.backend, staging
                ).getOrThrow()
                models.select(model.id)
                model.name
            }
        )
        if (!started) ctx.toast(R.string.download_in_progress)
        announced.remove(entry.id)
    }

    /** Rows follow the application-scoped download state, so navigation does not matter. */
    private fun observeDownloads() {
        if (observeJob != null) return
        observeJob = viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.states.collect { states ->
                states.forEach { (id, progress) ->
                    downloadRows[id]?.summary = progressSuffix(progress)
                    if (progress.phase == DownloadManager.Phase.Done && announced.add(id)) {
                        context?.toast(getString(R.string.download_done, progress.message))
                        DownloadManager.clear(id)
                        buildScreen()
                    }
                }
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
                    Timber.w(it, "import voice model failed")
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
