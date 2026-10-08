package moe.chenxy.huaweipods.hook

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.supportsAnc
import moe.chenxy.huaweipods.pods.supportsTransparency
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams

internal const val COLOR_OS_WEAR_NOT_WEARING = 1
internal const val COLOR_OS_LOW_BATTERY_THRESHOLD = 20

/**
 * 佩戴状态：充电中的耳机在盒内；其余在线耳机按耳机上报的整体入耳状态区分佩戴 / 未佩戴，
 * 尚未收到上报时视为佩戴（系统据此隐藏盒电量）。华为协议不区分左右耳是否入耳。
 */
internal fun colorOsEarbudWearStatus(pod: PodParams?, inEar: Boolean? = null): Int = when {
    pod == null || !pod.isConnected -> COLOR_OS_WEAR_UNKNOWN
    pod.isCharging -> COLOR_OS_WEAR_IN_CASE
    inEar == false -> COLOR_OS_WEAR_NOT_WEARING
    else -> COLOR_OS_WEAR_WEARING
}

/** “我的设备”降噪切换项按机型能力列出，顺序与系统 AirPods 页面一致。 */
internal fun colorOsNoiseControlOptions(route: HuaweiDeviceRoute): List<NoiseControlMode> {
    if (!route.supportsAnc) return emptyList()
    return buildList {
        add(NoiseControlMode.OFF)
        add(NoiseControlMode.NOISE_CANCELLATION)
        if (route.supportsTransparency) add(NoiseControlMode.TRANSPARENCY)
    }
}

/** 在线且未充电的耳机电量不高于阈值时提醒一次；全部回到阈值以上或开始充电后重新布防。 */
internal class ColorOsLowBatteryTracker(private val threshold: Int = COLOR_OS_LOW_BATTERY_THRESHOLD) {
    private val notified = mutableSetOf<String>()

    @Synchronized
    fun shouldNotify(address: String, battery: BatteryParams): Boolean {
        if (lowEarbuds(battery).isEmpty()) {
            notified.remove(address)
            return false
        }
        return notified.add(address)
    }

    fun lowEarbuds(battery: BatteryParams): List<Pair<Boolean, Int>> =
        listOfNotNull(
            battery.left?.let { true to it },
            battery.right?.let { false to it },
        ).filter { (_, pod) -> pod.isConnected && !pod.isCharging && pod.battery in 1..threshold }
            .map { (isLeft, pod) -> isLeft to pod.battery }

    @Synchronized
    fun reset(address: String) {
        notified.remove(address)
    }

    @Synchronized
    fun clear() {
        notified.clear()
    }
}
