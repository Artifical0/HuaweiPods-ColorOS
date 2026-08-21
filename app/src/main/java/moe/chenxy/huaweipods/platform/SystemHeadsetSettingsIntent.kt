package moe.chenxy.huaweipods.platform

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import moe.chenxy.huaweipods.config.ConfigManager

object SystemHeadsetSettingsIntent {
    @Suppress("DEPRECATION")
    fun open(context: Context, device: BluetoothDevice): Boolean {
        val candidates = when (
            RomIntegrationPolicy.detect(
                manufacturer = android.os.Build.MANUFACTURER,
                brand = android.os.Build.BRAND,
            )
        ) {
            RomFamily.HYPER_OS -> listOf(miuiIntent(device), aospDetailIntent(device))
            RomFamily.COLOR_OS,
            RomFamily.GENERIC_ANDROID,
            -> listOf(aospDetailIntent(device), bluetoothSettingsIntent())
        }

        return candidates.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    private fun miuiIntent(device: BluetoothDevice) = Intent().apply {
        setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
        putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        putExtra("bluetoothaddress", device.address)
        putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
        putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
        putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
    }

    /**
     * AOSP and ColorOS Settings both retain the standard Bluetooth detail entry.
     * Passing the address in both locations covers Settings implementations that
     * read either the activity extra or the fragment argument bundle.
     */
    private fun aospDetailIntent(device: BluetoothDevice) = Intent().apply {
        setClassName(
            RomIntegrationPolicy.SETTINGS_PACKAGE,
            "com.android.settings.Settings\$BluetoothDeviceDetailActivity",
        )
        putExtra("device_address", device.address)
        putExtra(
            ":settings:show_fragment_args",
            Bundle().apply { putString("device_address", device.address) },
        )
        putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    private fun bluetoothSettingsIntent() = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}
