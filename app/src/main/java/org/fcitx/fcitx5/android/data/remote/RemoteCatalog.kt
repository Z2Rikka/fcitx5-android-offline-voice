/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.remote

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class CatalogFile(
    val url: String,
    /** File name inside the install directory (voice model) or path inside the Rime dir. */
    val name: String,
    val sha256: String = "",
    val size: Long = 0
)

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val note: String = "",
    /** [KIND_VOICE] or [KIND_DICT]. */
    val kind: String,
    /** Voice models only: `ZipformerTransducer` / `Paraformer`. */
    val backend: String = "",
    /** Dictionaries only: Rime schema id this package provides, e.g. `double_pinyin_flypy`. */
    val schema: String = "",
    val files: List<CatalogFile> = emptyList()
) {
    val sizeBytes: Long get() = files.sumOf { it.size }
    val isVoice: Boolean get() = kind == KIND_VOICE
    val isDict: Boolean get() = kind == KIND_DICT
}

@Serializable
data class Catalog(val version: Int = 1, val entries: List<CatalogEntry> = emptyList()) {
    fun ofKind(kind: String) = entries.filter { it.kind == kind }
    fun find(id: String) = entries.firstOrNull { it.id == id }
}

/**
 * Downloadable content list.
 *
 * Lookup order:
 *  1. `filesDir/catalog.json` — user override / last successful refresh
 *  2. `assets/catalog.json` — the catalog shipped with the APK
 *
 * The remote catalog is a plain JSON document (see [Catalog]); point [CATALOG_URL] at your own
 * copy (a gist or a repository file works) and use "刷新列表" in the settings to pull it.
 */
object RemoteCatalog {

    const val KIND_VOICE = "voice"
    const val KIND_DICT = "dict"

    /**
     * Where "刷新列表" fetches the catalog from.
     *
     * Nothing is downloaded implicitly: the catalog is only fetched when the user presses refresh,
     * and its entries are only downloaded when the user taps a specific entry.
     */
    const val CATALOG_URL =
        "https://raw.githubusercontent.com/fcitx5-android-offline/catalog/main/catalog.json"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private fun localFile(context: Context) = File(context.filesDir, "catalog.json")

    private fun read(text: String): Catalog? = runCatching {
        json.decodeFromString<Catalog>(text)
    }.onFailure {
        Timber.w(it, "RemoteCatalog: invalid catalog json")
    }.getOrNull()

    fun load(context: Context): Catalog {
        localFile(context).takeIf { it.isFile }?.let { file ->
            read(file.readText())?.let { return it }
        }
        return runCatching {
            read(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        }.getOrNull() ?: Catalog()
    }

    /** Fetch [url] (defaults to [CATALOG_URL]) and cache it for [load]. */
    suspend fun refresh(context: Context, url: String = CATALOG_URL): Result<Catalog> {
        return Downloader.fetchText(url).mapCatching { text ->
            val catalog = read(text) ?: error("catalog.json 格式不正确")
            localFile(context).writeText(json.encodeToString(catalog))
            catalog
        }
    }

    fun clearLocal(context: Context) {
        localFile(context).delete()
    }
}
