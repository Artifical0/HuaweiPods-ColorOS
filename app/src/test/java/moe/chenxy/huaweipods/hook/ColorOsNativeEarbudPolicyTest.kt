package moe.chenxy.huaweipods.hook

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsNativeEarbudPolicyTest {
    private val worn = PodParams(battery = 70, isConnected = true)
    private val charging = PodParams(battery = 70, isCharging = true, isConnected = true)

    @Test
    fun `in-ear report separates wearing from out of ear`() {
        assertEquals(COLOR_OS_WEAR_WEARING, colorOsEarbudWearStatus(worn, inEar = true))
        assertEquals(COLOR_OS_WEAR_NOT_WEARING, colorOsEarbudWearStatus(worn, inEar = false))
        assertEquals(COLOR_OS_WEAR_WEARING, colorOsEarbudWearStatus(worn, inEar = null))
    }

    @Test
    fun `charging earbuds stay in case regardless of in-ear report`() {
        assertEquals(COLOR_OS_WEAR_IN_CASE, colorOsEarbudWearStatus(charging, inEar = true))
        assertEquals(COLOR_OS_WEAR_IN_CASE, colorOsEarbudWearStatus(charging, inEar = false))
        assertEquals(COLOR_OS_WEAR_UNKNOWN, colorOsEarbudWearStatus(null, inEar = true))
    }

    @Test
    fun `noise control options follow model capabilities`() {
        assertEquals(
            listOf(NoiseControlMode.OFF, NoiseControlMode.NOISE_CANCELLATION, NoiseControlMode.TRANSPARENCY),
            colorOsNoiseControlOptions(HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5),
        )
        assertEquals(
            listOf(NoiseControlMode.OFF, NoiseControlMode.NOISE_CANCELLATION),
            colorOsNoiseControlOptions(HuaweiDeviceRoute.HUAWEI_FREEBUDS3),
        )
        assertTrue(colorOsNoiseControlOptions(HuaweiDeviceRoute.HUAWEI_FREECLIP).isEmpty())
    }

    @Test
    fun `low battery reminder fires once until recovered`() {
        val tracker = ColorOsLowBatteryTracker()
        val low = BatteryParams(left = PodParams(battery = 18, isConnected = true), right = worn)
        val recovered = BatteryParams(left = PodParams(battery = 60, isConnected = true), right = worn)

        assertTrue(tracker.shouldNotify("A", low))
        assertFalse(tracker.shouldNotify("A", low))
        assertFalse(tracker.shouldNotify("A", recovered))
        assertTrue(tracker.shouldNotify("A", low))
        assertEquals(listOf(true to 18), tracker.lowEarbuds(low))
    }

    @Test
    fun `charging or disconnected earbuds never trigger a low battery reminder`() {
        val tracker = ColorOsLowBatteryTracker()
        val battery = BatteryParams(
            left = PodParams(battery = 10, isCharging = true, isConnected = true),
            right = PodParams(battery = 5, isConnected = false),
        )

        assertFalse(tracker.shouldNotify("A", battery))
    }

    @Test
    fun `settings vendor allowlist mirrors the system property format`() {
        assertEquals(COLOR_OS_SETTINGS_DEFAULT_3RD_VENDORS, colorOsSettingsThirdPartyVendors(null))
        assertEquals(COLOR_OS_SETTINGS_DEFAULT_3RD_VENDORS, colorOsSettingsThirdPartyVendors("  "))
        assertEquals(listOf(0x0A80, 0x054C), colorOsSettingsThirdPartyVendors("0A80, 054c,zz"))
    }
}
