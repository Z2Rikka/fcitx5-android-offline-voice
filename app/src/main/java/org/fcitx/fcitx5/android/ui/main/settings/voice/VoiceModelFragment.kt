/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.VoiceModel
import org.fcitx.fcitx5.android.input.voice.VoiceModelManager
import org.fcitx.fcitx5.android.input.voice.VoiceModelSource
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

/**
 * Switch between the bundled / imported offline voice models and uninstall imported ones.
 */
class VoiceModelFragment : PaddingPreferenceFragment() {

    private val manager by lazy { VoiceModelManager(requireContext()) }

    private val importZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

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
