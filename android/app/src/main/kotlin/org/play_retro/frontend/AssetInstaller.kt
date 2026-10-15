//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  AssetInstaller.kt
//
//  Copies the bundled resources and theme from the APK to internal storage. Resources can
//  also be read directly from the APK through the AssetManager, but fonts (HarfBuzz),
//  message catalogs (libintl) and the bundled theme need to be regular files.
//

package org.play_retro.frontend

import android.content.Context
import java.io.File
import java.io.IOException

class AssetInstaller(context: Context) {
    private val assets = context.assets
    private val internalDir: File = context.filesDir

    private val resourcesDir get() = File(internalDir, "resources")
    private val themesDir get() = File(internalDir, "themes")

    fun installFonts() = copyResourceDirectory("fonts")

    fun installLocales() = copyResourceDirectory("locale")

    // Copies all resources and themes, replacing earlier versions. Returns false on failure.
    fun installAll(): Boolean = try {
        resourcesDir.deleteRecursively()
        themesDir.deleteRecursively()
        for (entry in assets.list("").orEmpty()) {
            when {
                entry == "themes" -> copy("themes", themesDir)
                isResourceEntry(entry) -> copy(entry, File(resourcesDir, entry))
            }
        }
        true
    } catch (e: IOException) {
        NativeBridge.error("AssetInstaller: Couldn't copy bundled resources: $e")
        false
    }

    private fun copyResourceDirectory(name: String) {
        try {
            val target = File(resourcesDir, name)
            target.deleteRecursively()
            copy(name, target)
        } catch (e: IOException) {
            NativeBridge.error("AssetInstaller: Couldn't copy $name: $e")
        }
    }

    // Skips entries added to the asset root by the platform or by libraries.
    private fun isResourceEntry(entry: String) =
        entry !in setOf("images", "webkit", "dexopt", "PublicSuffixDatabase.list")

    private fun copy(assetPath: String, target: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // A file, as directories in the APK are never empty.
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copy("$assetPath/$child", File(target, child))
    }
}
