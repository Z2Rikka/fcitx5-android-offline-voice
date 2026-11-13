/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import java.io.File

/** Seed this edition's private Rime directory once, without replacing later user changes. */
object BundledRime {
    fun install(context: Context) {
        if (org.fcitx.fcitx5.android.FcitxApplication.getInstance().isDirectBootMode) return
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        val rime = File(external, "data/rime")
        val marker = File(rime, ".offline-bundle-v1")
        if (marker.exists()) return
        fun copyAsset(directory: String, destination: File) {
            val children = context.assets.list(directory).orEmpty()
            if (children.isEmpty()) {
                if (!destination.exists()) {
                    destination.parentFile?.mkdirs()
                    val temporary = File(destination.parentFile, destination.name + ".tmp")
                    context.assets.open(directory).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.renameTo(destination)) { "Cannot install ${destination.name}" }
                }
            } else {
                destination.mkdirs()
                children.forEach { copyAsset("$directory/$it", File(destination, it)) }
            }
        }
        copyAsset("bundled-rime", rime)
        val profile = File(external, "config/profile")
        if (!profile.exists()) {
            profile.parentFile?.mkdirs()
            profile.writeText("""
                [Groups/0]
                Name=Default
                Default Layout=us
                DefaultIM=rime

                [Groups/0/Items/0]
                Name=keyboard-us
                Layout=

                [Groups/0/Items/1]
                Name=rime
                Layout=

                [GroupOrder]
                0=Default
            """.trimIndent() + "\n")
        }
        marker.writeText("1\n")
    }
}
