package moe.chenxy.huaweipods.platform

enum class RomFamily {
    HYPER_OS,
    COLOR_OS,
    GENERIC_ANDROID,
}

object RomIntegrationPolicy {
    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    const val SETTINGS_PACKAGE = "com.android.settings"
    const val XIAOMI_BLUETOOTH_PACKAGE = "com.xiaomi.bluetooth"
    const val MILINK_PACKAGE = "com.milink.service"
    const val OPLUS_MELODY_PACKAGE = "com.oplus.melody"
    const val OPLUS_WIRELESS_SETTINGS_PACKAGE = "com.oplus.wirelesssettings"
    const val HEYTAP_ACCESSORY_PACKAGE = "com.heytap.accessory"
    const val HEYTAP_MY_DEVICES_PACKAGE = "com.heytap.mydevices"

    fun detect(manufacturer: String?, brand: String?): RomFamily {
        val identity = listOf(manufacturer, brand)
            .joinToString(" ")
            .lowercase()
        return when {
            listOf("oppo", "oneplus", "realme", "oplus").any(identity::contains) ->
                RomFamily.COLOR_OS
            listOf("xiaomi", "redmi", "poco").any(identity::contains) ->
                RomFamily.HYPER_OS
            else -> RomFamily.GENERIC_ANDROID
        }
    }

    /**
     * The Bluetooth process hosts the Huawei protocol engine on every supported ROM.
     * Xiaomi additionally needs its separate Bluetooth UI process for the existing
     * HyperOS connection-card integration. ColorOS can use the module UI with only
     * the Bluetooth scope while vendor-host UI work remains version-specific.
     */
    fun requiredCoreScopes(family: RomFamily): Set<String> = when (family) {
        RomFamily.HYPER_OS -> setOf(BLUETOOTH_PACKAGE, XIAOMI_BLUETOOTH_PACKAGE)
        RomFamily.COLOR_OS,
        RomFamily.GENERIC_ANDROID,
        -> setOf(BLUETOOTH_PACKAGE)
    }

    fun colorOsHostPackages(): Set<String> = setOf(
        HEYTAP_ACCESSORY_PACKAGE,
        HEYTAP_MY_DEVICES_PACKAGE,
        OPLUS_MELODY_PACKAGE,
        OPLUS_WIRELESS_SETTINGS_PACKAGE,
    )

    fun notificationHostPackage(family: RomFamily): String = when (family) {
        RomFamily.HYPER_OS -> XIAOMI_BLUETOOTH_PACKAGE
        RomFamily.COLOR_OS,
        RomFamily.GENERIC_ANDROID,
        -> BLUETOOTH_PACKAGE
    }
}
