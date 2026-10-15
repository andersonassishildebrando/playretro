//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  AppSettings.kt
//
//  Settings owned by the Android layer: the directories chosen during onboarding and the
//  state of the bundled resources. Everything else is kept by the C++ application in
//  es_settings.xml inside the application data directory.
//

package org.play_retro.frontend

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("playretro", Context.MODE_PRIVATE)

    var appDataDirectory: String
        get() = prefs.getString(KEY_APP_DATA_DIR, "") ?: ""
        set(value) = prefs.edit { putString(KEY_APP_DATA_DIR, value) }

    var romDirectory: String
        get() = prefs.getString(KEY_ROM_DIR, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ROM_DIR, value) }

    // Storage Access Framework tree URI of the ROM directory, used for %ROMSAF% launches.
    var romDirectoryTreeUri: String
        get() = prefs.getString(KEY_ROM_DIR_URI, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ROM_DIR_URI, value) }

    var createSystemDirectories: Boolean
        get() = prefs.getBoolean(KEY_CREATE_SYSTEM_DIRS, false)
        set(value) = prefs.edit { putBoolean(KEY_CREATE_SYSTEM_DIRS, value) }

    var resourceBuildIdentifier: String
        get() = prefs.getString(KEY_RESOURCE_BUILD_ID, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RESOURCE_BUILD_ID, value) }

    private companion object {
        const val KEY_APP_DATA_DIR = "app_data_directory"
        const val KEY_ROM_DIR = "rom_directory"
        const val KEY_ROM_DIR_URI = "rom_directory_tree_uri"
        const val KEY_CREATE_SYSTEM_DIRS = "create_system_directories"
        const val KEY_RESOURCE_BUILD_ID = "resource_build_identifier"
    }
}
