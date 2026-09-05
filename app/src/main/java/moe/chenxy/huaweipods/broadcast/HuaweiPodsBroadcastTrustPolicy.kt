package moe.chenxy.huaweipods.broadcast

import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

/**
 * 跨进程广播信任策略。
 *
 * HuaweiPods 需要在 App、Bluetooth 宿主进程 (com.android.bluetooth)、系统设置 (com.android.settings)、
 * 智慧音频 (com.huawei.smartaudio) 和负一屏等进程之间传递状态与控制指令。
 * 为防止任意普通第三方应用伪造控制广播，所有跨进程接收端均采用 fail-closed 原则校验发送方包名。
 */
object HuaweiPodsBroadcastTrustPolicy {
    const val PACKAGE_APP = "io.github.artifical0.huaweipods.coloros"
    const val PACKAGE_BLUETOOTH = "com.android.bluetooth"
    const val PACKAGE_SETTINGS = "com.android.settings"
    const val PACKAGE_SMART_AUDIO = "com.huawei.smartaudio"
    const val PACKAGE_MILINK = "com.milink.service"
    const val PACKAGE_HEYTAP_ACCESSORY = "com.heytap.accessory"
    const val PACKAGE_HEYTAP_MYDEVICES = "com.heytap.mydevices"

    /** 受信任的控制广播发送方（由模块 App 发往蓝牙等后台宿主进程） */
    private val trustedAppControlSenders = setOf(
        PACKAGE_APP,
    )

    /** 受信任的蓝牙底层状态广播发送方（由蓝牙进程发往各 UI 与宿主） */
    private val trustedBluetoothStateSenders = setOf(
        PACKAGE_BLUETOOTH,
        "com.xiaomi.bluetooth",
    )

    /**
     * 判断是否为合法的控制类广播发送者（例如 ACTION_CONNECT_POD_REQUEST, ACTION_PODS_UI_INIT, ACTION_REFRESH_STATUS）。
     */
    fun isTrustedAppControlSender(packageName: String?): Boolean =
        packageName != null && packageName in trustedAppControlSenders

    /**
     * 判断是否为合法的蓝牙状态类广播发送者（例如 ACTION_PODS_CONNECTED, ACTION_PODS_DISCONNECTED, ACTION_PODS_BATTERY_CHANGED 等）。
     */
    fun isTrustedBluetoothStateSender(packageName: String?): Boolean =
        packageName != null && packageName in trustedBluetoothStateSenders

    /**
     * 判断是否为合法的智慧音频发送者。
     */
    fun isTrustedSmartAudioSender(packageName: String?): Boolean =
        packageName == PACKAGE_SMART_AUDIO

    /**
     * 根据广播 Action 与发送者包名，综合评估是否信任该广播。
     */
    fun shouldAcceptBroadcast(action: String?, senderPackage: String?): Boolean {
        if (action.isNullOrBlank()) return false
        val canonicalAction = HuaweiPodsAction.canonical(action)
        return when (canonicalAction) {
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST ->
                isTrustedAppControlSender(senderPackage)

            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_CHANGED,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_RESULT ->
                isTrustedBluetoothStateSender(senderPackage)

            else -> true
        }
    }
}
