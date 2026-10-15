//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  AppImporter.kt
//
//  Lists installed apps for the game importer and exports their icons (and optionally
//  banners) as PNG files for use as game media.
//

package org.play_retro.frontend

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.File

class AppImporter(private val context: Context) {

    // Returns a flat array of (display name, package name) pairs.
    fun listApps(appDataDirectory: String, gamesOnly: Boolean, importMedia: Boolean): Array<String> {
        val packageManager = context.packageManager
        val tempDir = File(appDataDirectory, "importer_temp")
        val iconDir = File(tempDir, "icons").apply { deleteRecursively(); mkdirs() }
        val mediaDir = File(tempDir, "media").apply { deleteRecursively(); mkdirs() }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .filter { !gamesOnly || isGame(it) }

        val usedNames = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (app in apps) {
            val baseName = sanitizeFileName(packageManager.getApplicationLabel(app).toString())
                .ifEmpty { app.packageName }
            var name = baseName
            var suffix = 1
            while (!usedNames.add(name)) name = "$baseName (${suffix++})"

            result += name
            result += app.packageName

            runCatching {
                writePng(packageManager.getApplicationIcon(app), File(iconDir, "$name.png"))
                if (importMedia) {
                    (packageManager.getApplicationBanner(app) ?: packageManager.getApplicationLogo(app))
                        ?.let { writePng(it, File(mediaDir, "$name.png")) }
                }
            }.onFailure { NativeBridge.warning("AppImporter: Couldn't export media for $name: $it") }
        }
        return result.toTypedArray()
    }

    @Suppress("DEPRECATION")
    private fun isGame(app: ApplicationInfo) =
        app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    // Removes characters that aren't allowed in file names on common file systems.
    private fun sanitizeFileName(name: String) = name.filterNot { it in INVALID_FILE_NAME_CHARS }.trim()

    private fun writePng(drawable: Drawable, file: File) {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val INVALID_FILE_NAME_CHARS = "\"*/:<>?\\|"
        const val DEFAULT_ICON_SIZE = 192
    }
}
