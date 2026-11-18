/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Minimal downloader for the optional voice models / Rime dictionaries.
 *
 * Features:
 *  - writes `<target>.part` and only renames once size and (when provided) SHA-256 match;
 *  - **resumes** an interrupted `.part` with an HTTP `Range` request (falls back to a fresh
 *    download when the server ignores the range);
 *  - cancellation is cooperative: the caller's coroutine scope decides, the `.part` is removed;
 *  - works with both `https://` and `http://` (the app opts into cleartext traffic).
 */
object Downloader {

    private const val USER_AGENT = "fcitx5-android-offline"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val BUFFER = 64 * 1024

    suspend fun fetchText(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url)
            try {
                check(connection.responseCode in 200..299) {
                    "HTTP ${connection.responseCode}"
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Timber.w(it, "Downloader: cannot fetch %s", url) }
    }

    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String = "",
        expectedSize: Long = 0,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".part")
            var written = if (partial.isFile) partial.length() else 0L
            if (expectedSize > 0 && written > expectedSize) {
                partial.delete()
                written = 0
            }
            val connection = open(url, written)
            var total = expectedSize
            var append = false
            try {
                val code = connection.responseCode
                append = written > 0 && code == HttpURLConnection.HTTP_PARTIAL
                if (!append && written > 0) {
                    // server ignored the range request (or the file changed), start over
                    Timber.d("Downloader: %s does not support resume, restarting", target.name)
                    partial.delete()
                    written = 0
                }
                check(code in 200..299) { "HTTP $code" }
                val contentLength = connection.contentLengthLong
                if (contentLength > 0) total = if (append) written + contentLength else contentLength
                connection.inputStream.use { input ->
                    partial.outputStream(append).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var reported = written
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            if (written - reported >= 256 * 1024) {
                                reported = written
                                onProgress(written, total)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            onProgress(written, total)
            check(expectedSize <= 0 || written == expectedSize) {
                "大小校验失败（$written/$expectedSize）"
            }
            if (expectedSha256.isNotBlank()) {
                val actual = sha256(partial)
                check(actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    "SHA-256 校验失败"
                }
            }
            check(partial.renameTo(target)) { "无法写入 ${target.name}" }
            Timber.d("Downloader: %s -> %s (%d bytes)", url, target.name, written)
            target
        }.onFailure {
            Timber.w(it, "Downloader: failed %s", url)
            if (it is kotlinx.coroutines.CancellationException) {
                // user cancelled: do not keep a half file around
                File(target.parentFile, target.name + ".part").delete()
            }
        }
    }

    /** Delete a stale partial file, e.g. after the catalog changed the expected content. */
    fun discardPartial(target: File) {
        File(target.parentFile, target.name + ".part").delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String, rangeStart: Long = 0): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (rangeStart > 0) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        return connection
    }
}
