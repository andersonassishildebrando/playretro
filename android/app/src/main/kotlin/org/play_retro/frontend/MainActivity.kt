//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  MainActivity.kt
//
//  Hosts the SDL/OpenGL application and exposes the platform functions called from C++
//  (es-core/src/utils/PlatformUtilAndroid.cpp). The JNI signatures used there must match
//  the @JvmStatic functions below.
//

package org.play_retro.frontend

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import org.libsdl.app.SDLActivity
import java.io.File

open class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("SDL2", "main")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { NativeBridge.nativeSetHomeApp(this is MainActivityHomeApp) }
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // SDL locks the orientation to the aspect ratio of the window it creates. The interface
    // reloads itself on window size changes, so always follow the sensor instead, regardless
    // of the system rotation lock.
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        // Ask for the Bluetooth permission used by the status bar indicator, once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean(PREF_BLUETOOTH_ASKED, false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_BLUETOOTH_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 0)
        }
    }

    companion object {
        private const val PREF_BLUETOOTH_ASKED = "bluetooth_permission_asked"

        private val context: Context get() = SDLActivity.getContext()
        private val settings get() = AppSettings(context)

        // Onboarding and directories.

        @JvmStatic
        fun checkConfigurationNeeded(): Boolean {
            if (!hasStoragePermission(context)) return true
            val s = settings
            return s.appDataDirectory.isEmpty() || s.romDirectory.isEmpty() ||
                !File(s.appDataDirectory).isDirectory || !File(s.romDirectory).isDirectory
        }

        @JvmStatic
        fun startConfigurator() {
            val activity = SDLActivity.mSingleton ?: return
            OnboardingActivity.pending = true
            activity.runOnUiThread {
                activity.startActivity(Intent(activity, OnboardingActivity::class.java))
            }
        }

        @JvmStatic fun getAppDataDirectory(): String = settings.appDataDirectory

        @JvmStatic fun getROMDirectory(): String = settings.romDirectory

        @JvmStatic fun getInternalDataDirectory(): String = context.filesDir.absolutePath

        @JvmStatic
        fun getExternalDataDirectory(): String =
            context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath

        // Per-user roots used for %INTERNALDATA% and %EXTERNALDATA% in launch commands.
        @JvmStatic
        fun getUserInternalDirectory(): String =
            context.dataDir.parentFile?.absolutePath ?: "/data/user/0"

        @JvmStatic
        fun getUserExternalDirectory(): String =
            Environment.getExternalStorageDirectory().absolutePath

        // Returns whether onboarding requested creation of the system directories; the
        // request is consumed.
        @JvmStatic
        fun getCreateSystemDirectories(): Boolean {
            val s = settings
            val create = s.createSystemDirectories
            if (create) s.createSystemDirectories = false
            return create
        }

        // Bundled assets.

        // The native build identifier only changes when the C++ code is rebuilt, so the APK
        // install time is added to also pick up updates that only change the bundled assets.
        private fun resourceIdentifier(buildIdentifier: String): String {
            val installed = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            return "$buildIdentifier, installed $installed"
        }

        @JvmStatic
        fun checkNeedResourceCopy(buildIdentifier: String): Boolean =
            settings.resourceBuildIdentifier != resourceIdentifier(buildIdentifier) ||
                !File(context.filesDir, "resources").isDirectory

        @JvmStatic fun setupFontFiles() = AssetInstaller(context).installFonts()

        @JvmStatic fun setupLocalizationFiles() = AssetInstaller(context).installLocales()

        @JvmStatic
        fun setupResources(buildIdentifier: String): Boolean {
            val success = AssetInstaller(context).installAll()
            if (success) settings.resourceBuildIdentifier = resourceIdentifier(buildIdentifier)
            return success
        }

        // Device state.

        @JvmStatic fun getDeviceInfo(): String = DeviceStatus(context).description()

        @JvmStatic fun isHomeApp(): Boolean = SDLActivity.mSingleton is MainActivityHomeApp

        @JvmStatic
        fun getWindowSize(): IntArray {
            val activity = SDLActivity.mSingleton ?: return intArrayOf(0, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                intArrayOf(bounds.width(), bounds.height())
            } else {
                val metrics = activity.resources.displayMetrics
                intArrayOf(metrics.widthPixels, metrics.heightPixels)
            }
        }

        @JvmStatic fun getBluetoothStatus(): Boolean = DeviceStatus(context).isBluetoothConnected()

        @JvmStatic fun getWifiStatus(): Int = DeviceStatus(context).wifiStatus()

        @JvmStatic fun getCellularStatus(): Int = DeviceStatus(context).cellularStatus()

        @JvmStatic fun getBatteryStatus(): IntArray = DeviceStatus(context).batteryStatus()

        // Emulators and native apps.

        @JvmStatic
        fun checkEmulatorInstalled(packageName: String, activity: String): Boolean {
            val packageManager = context.packageManager
            if (packageManager.getLaunchIntentForPackage(packageName) == null) {
                NativeBridge.debug("checkEmulatorInstalled(): Package $packageName not found")
                return false
            }
            if (activity.isEmpty()) return true
            val activityName = if (activity.startsWith(".")) packageName + activity else activity
            return try {
                val activities = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities
                val found = activities?.any { it.name == activityName && it.exported } == true
                if (!found) {
                    NativeBridge.debug("checkEmulatorInstalled(): No exported activity $activityName in $packageName")
                }
                found
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        // Querying RetroArch for installed cores isn't supported yet; -2 makes the C++
        // side proceed with the launch.
        @JvmStatic fun checkRACoreInstalled(packageName: String, coreFile: String): Int = -2

        @JvmStatic
        fun launchGame(
            packageName: String,
            activity: String,
            action: String,
            category: String,
            mimeType: String,
            data: String,
            startPath: String,
            romRaw: String,
            extrasString: Array<String>,
            extrasStringArray: Array<String>,
            extrasInteger: Array<String>,
            extrasBool: Array<String>,
            activityFlags: Array<String>,
            launchOnOtherScreen: Boolean,
        ): Int {
            val launcherContext = SDLActivity.mSingleton ?: context
            return GameLauncher(launcherContext, settings).launch(
                GameLauncher.LaunchRequest(
                    packageName = packageName,
                    activity = activity,
                    action = action,
                    category = category,
                    mimeType = mimeType,
                    data = data,
                    romPath = romRaw,
                    extrasString = pairsToMap(extrasString),
                    extrasStringArray = pairsToMap(extrasStringArray),
                    extrasInteger = pairsToMap(extrasInteger),
                    extrasBool = pairsToMap(extrasBool),
                    activityFlags = activityFlags.toList(),
                    launchOnOtherScreen = launchOnOtherScreen,
                ),
            )
        }

        @JvmStatic
        fun getInstalledApps(gamesOnly: Boolean, importMedia: Boolean): Array<String> =
            AppImporter(context).listApps(settings.appDataDirectory, gamesOnly, importMedia)

        fun hasStoragePermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }

        // Maps are passed from C++ as flat key/value arrays.
        private fun pairsToMap(flat: Array<String>): Map<String, String> =
            flat.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    }
}
