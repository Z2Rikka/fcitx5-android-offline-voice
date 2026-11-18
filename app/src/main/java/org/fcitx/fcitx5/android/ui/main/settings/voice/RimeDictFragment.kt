/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.fcitx.fcitx5.android.data.remote.Downloader
import org.fcitx.fcitx5.android.data.remote.RemoteCatalog
import org.fcitx.fcitx5.android.data.rime.RimeDictManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File

/**
 * Manage Rime schemes and dictionaries: switch the active scheme, install extra dictionaries from
 * the remote catalog, uninstall them, and redeploy Rime so librime recompiles what changed.
 */
class RimeDictFragment : PaddingPreferenceFragment() {

    private val manager by lazy { RimeDictManager(requireContext()) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    private fun buildScreen() {
        val ctx = requireContext()
        val schemes = manager.schemes()
        val enabled = manager.enabledSchemes()
        val active = enabled.firstOrNull()
        val installed = manager.installed()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.rime_dict_schemes) {
                if (schemes.isEmpty()) {
                    addPreference(R.string.rime_dict_none)
                }
                schemes.forEach { scheme ->
                    val current = scheme.id == active
                    val action: (() -> Unit)? = if (current) null else {
                        {
                            if (manager.activate(scheme.id)) {
                                redeploy()
                            }
                            buildScreen()
                        }
                    }
                    addPreference(
                        title = if (current) "✓ ${scheme.name}" else scheme.name,
                        summary = if (current) {
                            getString(R.string.rime_dict_current)
                        } else {
                            scheme.id
                        },
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
                        setOnPreferenceClickListener { clicked ->
                            startDownload(entry, clicked)
                            true
                        }
                    }
                    addPreference(row)
                }
                installed.forEach { dict ->
                    addPreference(
                        title = ctx.getString(R.string.rime_dict_uninstall_name, dict.name),
                        summary = dict.files.joinToString(", "),
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
                    entry.files.forEach { file ->
                        Downloader.download(
                            url = file.url,
                            target = File(staging, file.name),
                            expectedSha256 = file.sha256,
                            expectedSize = file.size
                        ) { done, total ->
                            if (total <= 0) return@download
                            val percent = (done * 100 / total).toInt()
                            mainHandler.post {
                                row?.summary = ctx.getString(R.string.download_progress, percent)
                            }
                        }.getOrThrow()
                    }
                    manager.install(entry, staging).getOrThrow()
                }
            }
            result
                .onSuccess {
                    ctx.toast(getString(R.string.download_done, it.name))
                    redeploy()
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

    private fun confirmUninstall(id: String, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.offline_voice_model_uninstall)
            .setMessage(getString(R.string.rime_dict_uninstall_confirm, name))
            .setPositiveButton(R.string.offline_voice_model_uninstall) { _, _ ->
                if (manager.uninstall(id)) {
                    redeploy()
                }
                buildScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Rime only picks up new/changed dictionaries during a deploy; restarting fcitx triggers it. */
    private fun redeploy() {
        requireContext().toast(R.string.rime_dict_redeploying)
        FcitxDaemon.restartFcitx()
    }

    private companion object {
        const val SEPARATOR = " · "
    }
}
