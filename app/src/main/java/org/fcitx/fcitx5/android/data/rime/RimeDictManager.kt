/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.rime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.remote.CatalogEntry
import timber.log.Timber
import java.io.File

/**
 * Manages the Rime user directory: which schemes are enabled in `default.custom.yaml`, which
 * downloaded dictionary packages are installed, and switching between them.
 *
 * The directory must match what fcitx5 uses as its user data dir (`<external>/data/rime`), i.e.
 * the one [org.fcitx.fcitx5.android.input.voice.BundledRime] seeds.
 *
 * Rime compiles dictionaries during its deploy step, so callers must call
 * `FcitxDaemon.restartFcitx()` afterwards (the settings screen has a "重新部署" button).
 */
class RimeDictManager(private val context: Context) {

    @Serializable
    private data class Manifest(
        val id: String,
        val name: String,
        val schema: String,
        val files: List<String>,
        /** Files that already existed before installing; their originals live in `<id>.bak/`. */
        val backups: List<String> = emptyList()
    )

    data class InstalledDict(
        val id: String,
        val name: String,
        val schema: String,
        val files: List<String>,
        val backups: List<String> = emptyList()
    )

    /** A scheme (`*.schema.yaml`) that exists on disk and can be enabled. */
    data class Scheme(val id: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val rimeDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "data/rime")

    private val customFile: File get() = File(rimeDir, "default.custom.yaml")
    private val manifestDir: File get() = File(rimeDir, MANIFEST_DIR)

    /** Originals of files that a download overwrote, so uninstalling can put them back. */
    private fun backupDir(id: String) = File(manifestDir, "$id.bak")

    fun stagingDir(id: String): File = File(rimeDir, ".download-$id")

    /** Schemes whose `*.schema.yaml` is present, with the human readable name if it can be read. */
    fun schemes(): List<Scheme> {
        val files = rimeDir.listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }
            ?: return emptyList()
        return files.map { file ->
            val id = file.name.removeSuffix(".schema.yaml")
            Scheme(id, schemaName(file) ?: id)
        }.filter { it.id !in AUXILIARY_SCHEMES }.sortedBy { it.name }
    }

    fun installed(): List<InstalledDict> {
        val files = manifestDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            runCatching {
                val m = json.decodeFromString<Manifest>(file.readText())
                InstalledDict(m.id, m.name, m.schema, m.files, m.backups)
            }.onFailure { Timber.w(it, "RimeDictManager: broken manifest %s", file.name) }
                .getOrNull()
        }
    }

    fun isInstalled(id: String) = File(manifestDir, "$id.json").isFile

    // ---- schema_list -------------------------------------------------------

    fun enabledSchemes(): List<String> {
        readSchemaList(customFile).let { if (it.isNotEmpty()) return it }
        return readSchemaList(File(rimeDir, "default.yaml"))
    }

    private fun readSchemaList(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val items = mutableListOf<String>()
        var inList = false
        file.readLines().forEach { line ->
            if (SCHEMA_LIST.matches(line)) {
                inList = true
                return@forEach
            }
            if (!inList) return@forEach
            val item = SCHEMA_ITEM.find(line)
            if (item != null) {
                items.add(item.groupValues[1])
                return@forEach
            }
            if (line.isBlank() || line.startsWith("    ") || line.startsWith("	")) return@forEach
            inList = false
        }
        return items
    }

    /** Activate [schema] by making it the first entry of `schema_list`. */
    fun activate(schema: String): Boolean {
        val current = enabledSchemes().toMutableList()
        current.remove(schema)
        current.add(0, schema)
        return setEnabledSchemes(current)
    }

    fun setEnabledSchemes(schemes: List<String>): Boolean = runCatching {
        val items = schemes.distinct()
        val lines = if (customFile.isFile) {
            customFile.readLines().toMutableList()
        } else {
            mutableListOf("patch:")
        }
        val block = items.map { "    - schema: $it" }
        val start = lines.indexOfFirst { SCHEMA_LIST.matches(it) }
        if (start >= 0) {
            var end = start + 1
            while (end < lines.size && (lines[end].isBlank() || lines[end].startsWith("    "))) end++
            lines.subList(start + 1, end).clear()
            lines.addAll(start + 1, block)
        } else {
            val patch = lines.indexOfFirst { it.trim() == "patch:" }
            if (patch >= 0) {
                lines.addAll(patch + 1, listOf("  schema_list:") + block)
            } else {
                lines.add("patch:")
                lines.add("  schema_list:")
                lines.addAll(block)
            }
        }
        rimeDir.mkdirs()
        customFile.writeText(lines.joinToString("\n").trimEnd() + "\n")
        Timber.i("RimeDictManager: enabled schemes = %s", items)
        true
    }.onFailure { Timber.w(it, "RimeDictManager: cannot write default.custom.yaml") }
        .getOrDefault(false)

    // ---- install / uninstall ----------------------------------------------

    /**
     * Move files downloaded into [staging] into the Rime directory and enable [entry]'s scheme.
     * Files keep the relative name given in the catalog.
     */
    fun install(entry: CatalogEntry, staging: File): Result<InstalledDict> = runCatching {
        check(staging.isDirectory) { "下载目录不存在" }
        // re-installing: restore what we overwrote last time, so the backup stays the original one
        if (isInstalled(entry.id)) uninstall(entry.id)
        val files = staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).path }.toList()
        check(files.isNotEmpty()) { "下载内容为空" }
        val target = rimeDir.apply { mkdirs() }
        val backups = mutableListOf<String>()
        val backup = backupDir(entry.id)
        files.forEach { relative ->
            val src = File(staging, relative)
            val dst = File(target, relative)
            dst.parentFile?.mkdirs()
            if (dst.isFile) {
                // keep the file that shipped with the app (or the user's own copy)
                val saved = File(backup, relative)
                saved.parentFile?.mkdirs()
                dst.copyTo(saved, overwrite = true)
                backups += relative
            }
            src.copyTo(dst, overwrite = true)
        }
        val installed = InstalledDict(
            id = entry.id,
            name = entry.name.ifBlank { entry.id },
            schema = entry.schema,
            files = files,
            backups = backups
        )
        File(manifestDir, entry.id + ".json").apply {
            parentFile?.mkdirs()
            writeText(
                json.encodeToString(
                    Manifest(installed.id, installed.name, installed.schema, installed.files, backups)
                )
            )
        }
        staging.deleteRecursively()
        // only switch when the file that defines the scheme really landed on disk
        if (entry.schema.isNotBlank() && File(target, entry.schema + ".schema.yaml").isFile) {
            activate(entry.schema)
        }
        Timber.i("RimeDictManager: installed %s (%d files, %d backups)", entry.id, files.size, backups.size)
        installed
    }.onFailure {
        Timber.w(it, "RimeDictManager: install(%s) failed", entry.id)
        staging.deleteRecursively()
    }

    /**
     * Delete the files that [install] created, put back anything it overwrote, and disable the
     * scheme (if any).
     */
    fun uninstall(id: String): Boolean = runCatching {
        val dict = installed().firstOrNull { it.id == id } ?: return false
        val backup = backupDir(id)
        dict.files.forEach { relative ->
            val file = File(rimeDir, relative)
            if (!file.canonicalPath.startsWith(rimeDir.canonicalPath)) return@forEach
            val saved = File(backup, relative)
            if (saved.isFile) {
                file.parentFile?.mkdirs()
                saved.copyTo(file, overwrite = true)
            } else if (file.isFile) {
                file.delete()
            }
        }
        backup.deleteRecursively()
        File(manifestDir, "$id.json").delete()
        if (dict.schema.isNotBlank()) {
            setEnabledSchemes(enabledSchemes().filter { it != dict.schema })
        }
        true
    }.onFailure { Timber.w(it, "RimeDictManager: uninstall(%s) failed", id) }
        .getOrDefault(false)

    // ---- helpers -----------------------------------------------------------

    private fun schemaName(file: File): String? = runCatching {
        file.useLines { lines ->
            var inSchema = false
            for (line in lines.take(SCHEMA_HEAD_LINES)) {
                when {
                    line.trimEnd() == "schema:" -> inSchema = true
                    inSchema && line.startsWith("  name:") ->
                        return@useLines line.removePrefix("  name:").trim()
                            .trim { c -> c == '"' || c.code == 39 }
                }
            }
            null
        }
    }.getOrNull()

    private companion object {
        const val MANIFEST_DIR = ".dict-manifests"
        const val SCHEMA_HEAD_LINES = 60

        /** Reverse-lookup / helper schemas that are not meant to be selected as the main scheme. */
        val AUXILIARY_SCHEMES = setOf("radical_pinyin")
        val SCHEMA_LIST = Regex("""^\s{2}schema_list:\s*$""")
        val SCHEMA_ITEM = Regex("""^\s*-\s*schema:\s*(\S+)\s*$""")
    }
}
