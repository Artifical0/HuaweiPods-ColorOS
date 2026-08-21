package moe.chenxy.huaweipods.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomIntegrationPolicyTest {
    @Test
    fun `detects ColorOS family brands`() {
        listOf("OPPO", "OnePlus", "realme", "OPlus").forEach { brand ->
            assertEquals(brand, RomFamily.COLOR_OS, RomIntegrationPolicy.detect(brand, brand))
        }
    }

    @Test
    fun `detects HyperOS family brands`() {
        listOf("Xiaomi", "Redmi", "POCO").forEach { brand ->
            assertEquals(brand, RomFamily.HYPER_OS, RomIntegrationPolicy.detect(brand, brand))
        }
    }

    @Test
    fun `ColorOS only requires the portable Bluetooth protocol scope`() {
        assertEquals(
            setOf(RomIntegrationPolicy.BLUETOOTH_PACKAGE),
            RomIntegrationPolicy.requiredCoreScopes(RomFamily.COLOR_OS),
        )
    }

    @Test
    fun `declares known ColorOS integration hosts`() {
        val packages = RomIntegrationPolicy.colorOsHostPackages()
        assertTrue("com.heytap.accessory" in packages)
        assertTrue("com.heytap.mydevices" in packages)
        assertTrue("com.oplus.melody" in packages)
        assertTrue("com.oplus.wirelesssettings" in packages)
    }

    @Test
    fun `routes notifications to the ROM Bluetooth host`() {
        assertEquals(
            RomIntegrationPolicy.XIAOMI_BLUETOOTH_PACKAGE,
            RomIntegrationPolicy.notificationHostPackage(RomFamily.HYPER_OS),
        )
        assertEquals(
            RomIntegrationPolicy.BLUETOOTH_PACKAGE,
            RomIntegrationPolicy.notificationHostPackage(RomFamily.COLOR_OS),
        )
    }
}
