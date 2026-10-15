//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  DeviceStatus.kt
//
//  Device state shown in the system status bar of the user interface.
//

package org.play_retro.frontend

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

class DeviceStatus(private val context: Context) {

    fun isBluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return try {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST_PROFILE).any {
                adapter.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun wifiStatus(): Int = transportStatus(NetworkCapabilities.TRANSPORT_WIFI)

    fun cellularStatus(): Int = transportStatus(NetworkCapabilities.TRANSPORT_CELLULAR)

    private fun transportStatus(transport: Int): Int {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return -1
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return 0
        return if (capabilities.hasTransport(transport)) 1 else 0
    }

    // [1 charging / 0 discharging, capacity in percent], or [-1, -1] without a battery.
    fun batteryStatus(): IntArray {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return intArrayOf(-1, -1)
        if (!battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return intArrayOf(-1, -1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return intArrayOf(if (charging) 1 else 0, percent)
    }

    fun description(): String = buildString {
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        append(", Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append(", ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    private companion object {
        // BluetoothProfile.HID_HOST is hidden from the public SDK.
        const val HID_HOST_PROFILE = 4
    }
}
