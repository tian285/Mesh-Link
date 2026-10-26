package com.example.blemesh.ui.permissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

object BlePermissions {
    /** Android 12+ : SCAN + CONNECT. Avant : la localisation est requise pour le scan BLE. */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasAll(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isBluetoothEnabled(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}

class BlePermissionState(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberBlePermissions(): BlePermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BlePermissions.hasAll(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = BlePermissions.hasAll(context) }
    return BlePermissionState(granted) { launcher.launch(BlePermissions.required().toTypedArray()) }
}
