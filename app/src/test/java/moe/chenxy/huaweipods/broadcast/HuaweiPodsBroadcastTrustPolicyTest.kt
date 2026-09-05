package moe.chenxy.huaweipods.broadcast

import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiPodsBroadcastTrustPolicyTest {

    @Test
    fun `trusted App sender should pass all control requests`() {
        val appSender = HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP
        assertTrue(HuaweiPodsBroadcastTrustPolicy.isTrustedAppControlSender(appSender))

        val controlActions = listOf(
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST,
        )
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
            "com.android.settings",
            "com.huawei.smartaudio",
        )
        val controlActions = listOf(
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST,
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
    fun `trusted bluetooth sender should pass state broadcasts`() {
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
    }
}
