/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.remote.CatalogEntry
import org.fcitx.fcitx5.android.data.remote.DownloadManager
import org.fcitx.fcitx5.android.data.remote.RemoteCatalog
import org.fcitx.fcitx5.android.data.rime.RimeDictManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast

/**
 * Manage Rime schemes and dictionaries: switch the active scheme, install extra dictionaries from
 * the remote catalog, uninstall them (restoring any bundled file they overwrote), and redeploy
 * Rime so librime recompiles what changed.
 *
 * Downloads are owned by [DownloadManager] (application scope): leaving this screen does not
 * interrupt them, several can run at once, and each row can cancel its own download.
 */
class RimeDictFragment : PaddingPreferenceFragment() {

    private val manager by lazy { RimeDictManager(requireContext()) }

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
        val schemes = manager.schemes()
        val active = manager.enabledSchemes().firstOrNull()
        val installed = manager.installed()
        downloadRows.clear()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.rime_dict_schemes) {
                if (schemes.isEmpty()) {
                    addPreference(R.string.rime_dict_none)
                }
                schemes.forEach { scheme ->
                    val current = scheme.id == active
                    val action: (() -> Unit)? = if (current) null else {
                        {
                            if (manager.activate(scheme.id)) redeploy()
                            buildScreen()
                        }
                    }
                    addPreference(
                        title = if (current) "✓ ${scheme.name}" else scheme.name,
                        summary = if (current) getString(R.string.rime_dict_current) else scheme.id,
                        onClick = action
                    )
                }
            }
            addCategory(R.string.rime_dict_downloads) {
                addPreference(R.string.catalog_refresh) { refreshCatalog() }
                val entries = RemoteCatalog.load(ctx).ofKind(RemoteCatalog.KIND_DICT)
                if (entries.isEmpty()) {
                    addPreference(R.string.rime_dict_none)
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
                installed.forEach { dict ->
                    addPreference(
                        title = ctx.getString(R.string.rime_dict_uninstall_name, dict.name),
                        summary = if (dict.backups.isEmpty()) {
                            dict.files.joinToString(", ")
                        } else {
                            // tell the user the bundled file will come back
                            getString(
                                R.string.rime_dict_uninstall_restores,
                                dict.files.joinToString(", ")
                            )
                        },
                        onClick = { confirmUninstall(dict.id, dict.name) }
                    )
                }
            }
            addCategory(R.string.rime_dict_category) {
                addPreference(R.string.rime_dict_redeploy, R.string.rime_dict_redeploy_summary) {
                    redeploy()
                }
            }
        }
    }

    private fun downloadSummary(entry: CatalogEntry): String {
        val parts = mutableListOf<String>()
        if (entry.schema.isNotBlank()) parts += entry.schema
        if (entry.note.isNotBlank()) parts += entry.note
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
                staging = RimeDictManager(appContext).stagingDir(entry.id),
                files = entry.files
            ) { staging ->
                val dict = RimeDictManager(appContext).install(entry, staging).getOrThrow()
                // deploy even when this screen is already gone, otherwise the new files are inert
                withContext(Dispatchers.Main) { FcitxDaemon.restartFcitx() }
                dict.name
            }
        )
        if (!started) ctx.toast(R.string.download_in_progress)
        announced.remove(entry.id)
    }

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

    private fun confirmUninstall(id: String, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.offline_voice_model_uninstall)
            .setMessage(getString(R.string.rime_dict_uninstall_confirm, name))
            .setPositiveButton(R.string.offline_voice_model_uninstall) { _, _ ->
                if (manager.uninstall(id)) redeploy()
                buildScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Rime only picks up new/changed dictionaries during a deploy; restarting fcitx triggers it. */
    private fun redeploy() {
        context?.toast(R.string.rime_dict_redeploying)
        FcitxDaemon.restartFcitx()
    }

    private companion object {
        const val SEPARATOR = " · "
    }
}
