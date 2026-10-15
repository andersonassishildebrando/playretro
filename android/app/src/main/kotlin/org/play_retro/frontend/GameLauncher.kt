//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  GameLauncher.kt
//
//  Builds and starts the Intent for launching a game in an emulator or a native app, from
//  the values parsed out of the es_systems.xml launch command by the C++ application
//  (see FileData::launchGame() for how the variables are parsed).
//

package org.play_retro.frontend

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import androidx.core.content.FileProvider
import java.io.File

class GameLauncher(private val context: Context, private val settings: AppSettings) {

    class LaunchRequest(
        val packageName: String,
        val activity: String,
        val action: String,
        val category: String,
        val mimeType: String,
        val data: String,
        val romPath: String,
        val extrasString: Map<String, String>,
        val extrasStringArray: Map<String, String>,
        val extrasInteger: Map<String, String>,
        val extrasBool: Map<String, String>,
        val activityFlags: List<String>,
        val launchOnOtherScreen: Boolean,
    )

    private class LaunchException(message: String) : Exception(message)

    // Returns 0 on success, nonzero on failure (matching the C++ return value convention).
    fun launch(request: LaunchRequest): Int {
        if (request.packageName == context.packageName) {
            NativeBridge.error("GameLauncher: Refusing to launch Play Retro from itself")
            return 1
        }
        return try {
            val intent = buildIntent(request)
            val options =
                if (request.launchOnOtherScreen) otherDisplayId()?.let {
                    ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle()
                } else null
            context.startActivity(intent, options)
            0
        } catch (e: LaunchException) {
            NativeBridge.error("GameLauncher: ${e.message}")
            1
        } catch (e: Exception) {
            NativeBridge.error("GameLauncher: Couldn't start activity: $e")
            1
        }
    }

    private fun buildIntent(request: LaunchRequest): Intent {
        val romPath = File(request.romPath).let {
            runCatching { it.canonicalPath }.getOrDefault(request.romPath)
        }
        val grantedUris = mutableListOf<Uri>()

        val intent = if (request.activity.isEmpty()) {
            context.packageManager.getLaunchIntentForPackage(request.packageName)
                ?: throw LaunchException("No launchable activity in package ${request.packageName}")
        } else {
            val activity =
                if (request.activity.startsWith(".")) request.packageName + request.activity
                else request.activity
            Intent().setComponent(ComponentName(request.packageName, activity))
        }

        if (request.action.isNotEmpty()) intent.action = request.action
        if (request.category.isNotEmpty()) intent.addCategory(request.category)

        val dataUri = if (request.data.isEmpty()) null else expandUri(request.data, romPath, grantedUris)
        when {
            dataUri != null && request.mimeType.isNotEmpty() ->
                intent.setDataAndType(dataUri, request.mimeType)
            dataUri != null -> intent.data = dataUri
            request.mimeType.isNotEmpty() -> intent.type = request.mimeType
        }

        for ((key, value) in request.extrasString) {
            val expanded = when (value) {
                "%ROM%" -> romPath
                "%ROMSAF%", "%ROMPROVIDER%" -> expandUri(value, romPath, grantedUris).toString()
                else -> value
            }
            intent.putExtra(key, expanded)
        }
        for ((key, value) in request.extrasStringArray) {
            // Commas separate the elements; an escaped comma (\,) is a literal comma.
            val elements = value.split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",") }
            intent.putExtra(key, elements.toTypedArray())
        }
        for ((key, value) in request.extrasInteger) {
            val number = value.trim().toIntOrNull()
                ?: throw LaunchException("Invalid integer extra $key=$value")
            intent.putExtra(key, number)
        }
        for ((key, value) in request.extrasBool) {
            intent.putExtra(key, parseBool(key, value))
        }

        for (flag in request.activityFlags) {
            when (flag) {
                "%ACTIVITY_CLEAR_TASK%" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                "%ACTIVITY_CLEAR_TOP%" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                "%ACTIVITY_NO_HISTORY%" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        if (grantedUris.isNotEmpty()) {
            // Grants also cover URIs passed in extras when they are listed in the clip data.
            val clip = ClipData.newRawUri("", grantedUris.first())
            grantedUris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        NativeBridge.debug("GameLauncher: Intent $intent, extras ${intent.extras?.let { b -> b.keySet().associateWith { b.get(it) } }}")
        return intent
    }

    private fun expandUri(value: String, romPath: String, grantedUris: MutableList<Uri>): Uri =
        when (value) {
            "%ROM%" -> Uri.parse(romPath)
            "%ROMSAF%" -> {
                val uri = StoragePaths.documentUriForPath(romPath, settings.romDirectoryTreeUri)
                    ?: throw LaunchException("Couldn't build a document URI for $romPath")
                grantedUris += uri
                uri
            }
            "%ROMPROVIDER%" -> {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(romPath))
                grantedUris += uri
                uri
            }
            else -> Uri.parse(value)
        }

    private fun parseBool(key: String, value: String): Boolean =
        when (value.trim().lowercase()) {
            "true", "t", "yes", "y" -> true
            "false", "f", "no", "n" -> false
            else -> value.trim().toIntOrNull()?.let { it != 0 }
                ?: throw LaunchException("Invalid boolean extra $key=$value")
        }

    // The first valid display other than the one Play Retro is shown on.
    private fun otherDisplayId(): Int? {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val current = context.display?.displayId ?: Display.DEFAULT_DISPLAY
        return displayManager.displays.firstOrNull { it.isValid && it.displayId != current }?.displayId
    }
}
