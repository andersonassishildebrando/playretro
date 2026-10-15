//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  NativeBridge.kt
//
//  Native callbacks into the C++ application, implemented in
//  es-core/src/utils/PlatformUtilAndroid.cpp.
//

package org.play_retro.frontend

import android.util.Log

object NativeBridge {
    private const val TAG = "PlayRetro"

    @JvmStatic external fun nativeSetHold(hold: Boolean)

    @JvmStatic external fun nativeSetHomeApp(isHomeApp: Boolean)

    @JvmStatic external fun nativeLog(message: String, level: Int)

    // Logs go to the application log file once the native library is loaded, and always
    // to logcat.
    fun error(message: String) = log(message, 0, Log.ERROR)

    fun warning(message: String) = log(message, 1, Log.WARN)

    fun info(message: String) = log(message, 2, Log.INFO)

    fun debug(message: String) = log(message, 3, Log.DEBUG)

    private fun log(message: String, level: Int, priority: Int) {
        Log.println(priority, TAG, message)
        try {
            nativeLog(message, level)
        } catch (_: UnsatisfiedLinkError) {
            // Native library not loaded yet.
        }
    }
}
