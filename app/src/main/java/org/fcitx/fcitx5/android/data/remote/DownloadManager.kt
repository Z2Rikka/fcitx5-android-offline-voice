/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Application-scoped download queue for voice models and Rime dictionaries.
 *
 * Everything runs in a singleton scope, so leaving the settings screen (or rotating) does not
 * interrupt a download. Several downloads may run at once, and each one can be cancelled.
 *
 * The screen only observes [states]; the install step is supplied by the caller and still runs
 * here, in the background.
 */
object DownloadManager {

    enum class Phase { Running, Done, Failed, Cancelled }

    data class Progress(
        val phase: Phase,
        val fileIndex: Int = 0,
        val fileCount: Int = 0,
        val downloaded: Long = 0,
        val total: Long = 0,
        /** Filled in for [Phase.Done] (installed name) and [Phase.Failed] (error message). */
        val message: String = ""
    ) {
        val percent: Int
            get() = when {
                phase != Phase.Running -> if (phase == Phase.Done) 100 else 0
                total <= 0 -> -1
                else -> (downloaded * 100 / total).toInt().coerceIn(0, 100)
            }
    }

    /**
     * @param install called with the fully downloaded [staging] directory, returns a display name
     *   for the toast / row. Runs off the main thread.
     */
    data class Task(
        val id: String,
        val staging: File,
        val files: List<CatalogFile>,
        val install: suspend (File) -> String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, Progress>>(emptyMap())

    val states: StateFlow<Map<String, Progress>> = _states.asStateFlow()

    fun progressOf(id: String): Progress? = _states.value[id]

    fun isRunning(id: String): Boolean = progressOf(id)?.phase == Phase.Running

    /** Start [task] unless the same id is already running. */
    fun start(task: Task): Boolean {
        synchronized(lock) {
            if (jobs[task.id]?.isActive == true) return false
            _states.update {
                it + (task.id to Progress(Phase.Running, fileCount = task.files.size))
            }
            jobs[task.id] = scope.launch {
                try {
                    runTask(task)
                } catch (e: CancellationException) {
                    task.staging.deleteRecursively()
                    _states.update { it + (task.id to Progress(Phase.Cancelled)) }
                    throw e
                } catch (e: Throwable) {
                    Timber.w(e, "DownloadManager: %s failed", task.id)
                    task.staging.deleteRecursively()
                    _states.update {
                        it + (task.id to Progress(Phase.Failed, message = e.message ?: ""))
                    }
                } finally {
                    synchronized(lock) { jobs.remove(task.id) }
                }
            }
            return true
        }
    }

    fun cancel(id: String): Boolean {
        val job = synchronized(lock) { jobs[id] } ?: return false
        job.cancel()
        return true
    }

    /** Forget a terminal state so the row can go back to "download" (e.g. after a failure). */
    fun clear(id: String) {
        if (isRunning(id)) return
        _states.update { it - id }
    }

    private suspend fun runTask(task: Task) {
        task.files.forEachIndexed { index, file ->
            val target = File(task.staging, file.name)
            Downloader.download(
                url = file.url,
                target = target,
                expectedSha256 = file.sha256,
                expectedSize = file.size
            ) { downloaded, total ->
                _states.update {
                    it + (task.id to Progress(
                        phase = Phase.Running,
                        fileIndex = index + 1,
                        fileCount = task.files.size,
                        downloaded = downloaded,
                        total = total
                    ))
                }
            }.getOrThrow()
        }
        val name = withContext(Dispatchers.IO) { task.install(task.staging) }
        _states.update {
            it + (task.id to Progress(Phase.Done, fileCount = task.files.size, message = name))
        }
        Timber.i("DownloadManager: %s installed as %s", task.id, name)
    }
}
