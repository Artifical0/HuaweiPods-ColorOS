package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import moe.chenxy.huaweipods.pods.displayName
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute

/**
 * ColorOS 16 "My Devices" integration, verified against com.heytap.mydevices 17.4.15.
 *
 * The vendor page already owns Bluetooth permissions and a stable system-styled settings row.
 * Reusing that row for supported Huawei devices avoids adding views with private COUI resources.
 * All reflection is guarded so a future My Devices update degrades to its stock behaviour.
 */
object ColorOsMyDevicesHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsMyDevices"
    private const val DETAIL_FRAGMENT =
        "com.oplus.mydevices.bluetooth.fragment.BtDetailPageFragment"
    private const val MODULE_PACKAGE = "moe.chenxy.huaweipods"
    private const val POPUP_ACTION = "chen.action.huaweipods.show_pods_ui"

    override fun onHook() {
        runCatching {
            val method = findMethod(
                DETAIL_FRAGMENT,
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
            )
            hookAfter(method) {
                val fragment = instance ?: return@hookAfter
                val root = args.firstOrNull() as? View ?: return@hookAfter
                runCatching { attachHuaweiPodsEntry(fragment, root) }
                    .onFailure { Log.w(TAG, "Unable to attach HuaweiPods entry", it) }
            }
            Log.i(TAG, "My Devices detail hook installed")
        }.onFailure {
            Log.w(TAG, "Unsupported My Devices build; leaving the stock page unchanged", it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachHuaweiPodsEntry(fragment: Any, root: View) {
        val address = getObjectField(fragment, "mMac") as? String
        val viewModel = getObjectField(fragment, "viewModel")
        val device = bluetoothDevice(address)
        val deviceName = runCatching { callMethod(viewModel, "u") as? String }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatching { device?.name ?: device?.alias }.getOrNull()
        val route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported || device == null) return

        val row = settingsRow(fragment, root) ?: run {
            Log.w(TAG, "Huawei device found but row_bt_setting is unavailable")
            return
        }
        customizeRow(row, route.displayName)
        row.setOnClickListener {
            launchHuaweiPods(row.context, device)
        }
        Log.i(TAG, "Attached entry for ${route.displayName}")
    }

    private fun settingsRow(fragment: Any, root: View): LinearLayout? {
        val bindingRow = runCatching {
            val binding = getObjectField(fragment, "_binding")
            getObjectField(binding, "h") as? LinearLayout
        }.getOrNull()
        if (bindingRow != null) return bindingRow

        val rowId = root.resources.getIdentifier("row_bt_setting", "id", packageName)
        return rowId.takeIf { it != 0 }?.let(root::findViewById)
    }

    private fun customizeRow(row: LinearLayout, deviceName: String) {
        row.isEnabled = true
        row.alpha = 1f
        row.contentDescription = "HuaweiPods, $deviceName"
        val labels = collectTextViews(row)
        when (labels.size) {
            0 -> Unit
            1 -> labels[0].text = "HuaweiPods · $deviceName"
            else -> {
                labels[0].text = "HuaweiPods"
                labels[1].text = if (Locale.getDefault().language == Locale.CHINESE.language) {
                    "$deviceName 电量与设置"
                } else {
                    "$deviceName battery and settings"
                }
            }
        }
    }

    private fun collectTextViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> addAll(collectTextViews(view.getChildAt(index))) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDevice(address: String?): BluetoothDevice? {
        if (address.isNullOrBlank()) return null
        return runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
    }

    private fun launchHuaweiPods(context: Context, device: BluetoothDevice) {
        val popupIntent = Intent(POPUP_ACTION).apply {
            setPackage(MODULE_PACKAGE)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(popupIntent) }
            .onFailure { error ->
                Log.w(TAG, "Popup unavailable; opening the module activity", error)
                runCatching {
                    context.startActivity(Intent().apply {
                        setClassName(MODULE_PACKAGE, "$MODULE_PACKAGE.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { Log.e(TAG, "Unable to open HuaweiPods", it) }
            }
    }
}
