/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Minimal HTTPS downloader for the optional voice models / Rime dictionaries.
 *
 * Files are written to `<target>.part` and only renamed once the size and (when provided) the
 * SHA-256 match, so a broken download can never be mistaken for a usable model.
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
            partial.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = open(url)
            var total = expectedSize
            var read = 0L
            var lastReport = 0L
            var lastTime = 0L
            try {
                check(connection.responseCode in 200..299) {
                    "HTTP ${connection.responseCode}"
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > 0) total = contentLength
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (read - lastReport >= 256 * 1024 || now - lastTime >= 200) {
                                lastReport = read
                                lastTime = now
                                onProgress(read, total)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            onProgress(read, total)
            check(expectedSize <= 0 || read == expectedSize) {
                "大小校验失败（$read/$expectedSize）"
            }
            if (expectedSha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    "SHA-256 校验失败"
                }
            }
            check(partial.renameTo(target)) { "无法写入 ${target.name}" }
            Timber.d("Downloader: %s -> %s (%d bytes)", url, target.name, read)
            target
        }.onFailure {
            Timber.w(it, "Downloader: failed %s", url)
            File(target.parentFile, target.name + ".part").delete()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }
}
