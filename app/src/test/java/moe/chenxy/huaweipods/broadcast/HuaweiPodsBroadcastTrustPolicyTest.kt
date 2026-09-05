package moe.chenxy.huaweipods.broadcast

import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiPodsBroadcastTrustPolicyTest {

    @Test
    fun `trusted App control senders should pass all control requests`() {
        val trustedSenders = listOf(
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_SETTINGS,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_MILINK,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_MYDEVICES,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_ACCESSORY,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_XIAOMI_BLUETOOTH,
        )
        trustedSenders.forEach { sender ->
            assertTrue("Sender $sender should be trusted for app control", HuaweiPodsBroadcastTrustPolicy.isTrustedAppControlSender(sender))
        }

        val controlActions = listOf(
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST,
            HuaweiPodsAction.ACTION_ANC_SELECT,
            HuaweiPodsAction.ACTION_CYCLE_ANC,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_SET,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_REFRESH,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_SET,
            HuaweiPodsAction.ACTION_HUAWEI_GESTURE_SET,
            HuaweiPodsAction.ACTION_FREECLIP2_AUDIO_SET,
            HuaweiPodsAction.ACTION_HUAWEI_EQUALIZER_PRESET_SET,
        )
        val appSender = HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP
        controlActions.forEach { action ->
            assertTrue(
                "Action $action should accept trusted app sender",
                HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, appSender),
            )
        }
    }

    @Test
    fun `untrusted sender or null should be rejected for control requests`() {
        val untrustedSenders = listOf(
            null,
            "",
            "com.malicious.fakeapp",
            "com.fake.settings",
            "com.other.app",
        )
        val controlActions = listOf(
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST,
            HuaweiPodsAction.ACTION_ANC_SELECT,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_SET,
            HuaweiPodsAction.ACTION_HUAWEI_GESTURE_SET,
        )
        for (sender in untrustedSenders) {
            assertFalse(
                "Sender $sender should not be trusted for app control",
                HuaweiPodsBroadcastTrustPolicy.isTrustedAppControlSender(sender),
            )
            for (action in controlActions) {
                assertFalse(
                    "Action $action should reject untrusted sender $sender",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, sender),
                )
            }
        }
    }

    @Test
    fun `trusted bluetooth sender should pass state and alive broadcasts`() {
        val bluetoothSender = HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH
        assertTrue(HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender(bluetoothSender))
        assertTrue(HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender("com.xiaomi.bluetooth"))

        val stateActions = listOf(
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_CHANGED,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_RESULT,
            HuaweiPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE,
            HuaweiPodsAction.ACTION_MODULE_MI_BLUETOOTH_SERVICE_ALIVE,
        )
        stateActions.forEach { action ->
            assertTrue(
                "Action $action should accept bluetooth sender",
                HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, bluetoothSender),
            )
        }
    }

    @Test
    fun `untrusted sender or null should be rejected for state broadcasts`() {
        val untrustedSenders = listOf(
            null,
            "",
            "com.fake.bluetooth",
            "io.github.artifical0.huaweipods.coloros",
            "com.tencent.mm",
        )
        val stateActions = listOf(
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE,
        )
        for (sender in untrustedSenders) {
            assertFalse(
                "Sender $sender should not be trusted for bluetooth state",
                HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender(sender),
            )
            for (action in stateActions) {
                assertFalse(
                    "Action $action should reject untrusted sender $sender",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, sender),
                )
            }
        }
    }

    @Test
    fun `smart audio sender should only pass smart audio domain`() {
        assertTrue(
            HuaweiPodsBroadcastTrustPolicy.isTrustedSmartAudioSender(
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_SMART_AUDIO,
            ),
        )
        assertFalse(HuaweiPodsBroadcastTrustPolicy.isTrustedSmartAudioSender(null))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.isTrustedSmartAudioSender("com.other.app"))

        val smartAudioAction = HuaweiPodsAction.ACTION_SMART_AUDIO_FREECLIP2_RESULT
        assertTrue(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                smartAudioAction,
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_SMART_AUDIO,
            ),
        )
        assertFalse(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                smartAudioAction,
                "com.fake.smartaudio",
            ),
        )
    }

    @Test
    fun `unknown action is strictly rejected fail-closed`() {
        val unknownActions = listOf(
            "chen.action.huaweipods.coloros.unknown_custom_action",
            "com.malicious.inject",
            "arbitrary.custom.broadcast",
            "",
            null,
        )
        val senders = listOf(
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
            "com.random.sender",
            null,
        )
        for (action in unknownActions) {
            assertEquals(BroadcastActionDomain.UNKNOWN, HuaweiPodsBroadcastTrustPolicy.resolveDomain(action.orEmpty()))
            for (sender in senders) {
                assertFalse(
                    "Action '$action' from sender '$sender' should be strictly rejected",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, sender),
                )
            }
        }
    }
}
