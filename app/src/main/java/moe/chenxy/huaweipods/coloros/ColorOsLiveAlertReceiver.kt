package moe.chenxy.huaweipods.coloros

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import moe.chenxy.huaweipods.MainActivity
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.platform.RomFamily
import moe.chenxy.huaweipods.platform.RomIntegrationPolicy
import moe.chenxy.huaweipods.utils.PodImageLoader
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

internal fun colorOsLiveAlertChipText(
    left: Int?,
    right: Int?,
    case: Int?,
): String? = listOfNotNull(left, right).minOrNull()?.let { "$it%" }
    ?: case?.let { "$it%" }

internal fun shouldShowColorOsLiveAlert(
    isColorOs: Boolean,
    hasConnectedBattery: Boolean,
): Boolean = isColorOs && hasConnectedBattery

internal fun shouldAcceptColorOsLiveAlertBatteryUpdate(
    lastDisconnectElapsed: Long,
    eventElapsed: Long,
): Boolean = lastDisconnectElapsed <= 0L || eventElapsed > lastDisconnectElapsed

/** Publishes the Android 16 promoted ongoing notification rendered as ColorOS Fluid Cloud. */
class ColorOsLiveAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action in TRUSTED_BLUETOOTH_ACTIONS &&
            sentFromPackage != BLUETOOTH_PACKAGE
        ) {
            Log.w(TAG, "Rejected headset status broadcast from an untrusted sender")
            return
        }
        val isColorOs = RomIntegrationPolicy.detect(Build.MANUFACTURER, Build.BRAND) ==
            RomFamily.COLOR_OS
        val manager = context.getSystemService(NotificationManager::class.java)
        val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (statePrefs.getLong(KEY_LAST_DISCONNECT_ELAPSED, 0L) == Long.MAX_VALUE) {
            // Migrate the old sentinel: it could reject every later battery event when
            // ColorOS omitted the matching connected broadcast.
            statePrefs.edit().putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L).apply()
        }
        val eventElapsed = intent.getLongExtra(
            HuaweiPodsAction.EXTRA_EVENT_ELAPSED_REALTIME,
            0L,
        )
        val platformDisconnect = when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED ->
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) in setOf(
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF,
                )

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val disconnected = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED,
                ) == BluetoothProfile.STATE_DISCONNECTED
                val eventAddress = runCatching {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )?.address
                }.getOrNull()
                val currentAddress = statePrefs.getString(KEY_CURRENT_ADDRESS, null)
                disconnected && !eventAddress.isNullOrBlank() &&
                    eventAddress.equals(currentAddress, ignoreCase = true)
            }

            else -> false
        }
        if (platformDisconnect) {
            cancelLiveAlert(context, manager, statePrefs, eventElapsed)
            Log.d(TAG, "ColorOS Live Alert cancelled on Bluetooth disconnect")
            return
        }
        when (HuaweiPodsAction.canonical(intent.action)) {
            HuaweiPodsAction.ACTION_PODS_CONNECTED -> {
                statePrefs.edit()
                    .putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
                    .putString(KEY_CURRENT_ADDRESS, intent.getStringExtra("address"))
                    .apply()
                return
            }

            HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> {
                cancelLiveAlert(context, manager, statePrefs, eventElapsed)
                Log.d(TAG, "ColorOS Live Alert cancelled on disconnect")
                return
            }

            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED -> {
                if (intent.getStringExtra("state") == "disconnected") {
                    cancelLiveAlert(context, manager, statePrefs, eventElapsed)
                    Log.d(TAG, "ColorOS Live Alert cancelled on connection-state change")
                } else if (intent.getStringExtra("state") == "connected") {
                    statePrefs.edit()
                        .putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
                        .putString(KEY_CURRENT_ADDRESS, intent.getStringExtra("address"))
                        .apply()
                }
                return
            }

            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> Unit
            else -> return
        }
        if (!isColorOs) return
        val lastDisconnectElapsed = statePrefs.getLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
        if (!shouldAcceptColorOsLiveAlertBatteryUpdate(lastDisconnectElapsed, eventElapsed)) {
            context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
            manager.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Ignored stale ColorOS Live Alert battery update")
            return
        }

        ConfigManager.refreshFromPrefs(
            context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE),
        )
        if (!ConfigManager.persistentNotificationEnabled() ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val battery = intent.batteryParams()
        intent.getStringExtra("address")?.takeIf(String::isNotBlank)?.let { address ->
            statePrefs.edit().putString(KEY_CURRENT_ADDRESS, address).apply()
        }
        val left = battery.left?.takeIf { it.isConnected }?.battery
        val right = battery.right?.takeIf { it.isConnected }?.battery
        val case = battery.case?.takeIf { it.isConnected }?.battery
        if (!shouldShowColorOsLiveAlert(isColorOs, listOf(left, right, case).any { it != null })) {
            context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.coloros_live_alert_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        val contentText = buildList {
            left?.let { add("L $it%") }
            right?.let { add("R $it%") }
            case?.let { add("${context.getString(R.string.pod_case)} $it%") }
        }.joinToString("  ")
        val address = intent.getStringExtra("address").orEmpty()
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val largeIcon = address.takeIf(String::isNotBlank)
            ?.let { PodImageLoader.loadBoxBitmap(context, prefs, it) }
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.img_freeclip_box)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(
                intent.getStringExtra("device_name")
                    ?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.coloros_live_alert_fallback_title),
            )
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply { largeIcon?.let(::setLargeIcon) }
            .apply {
                if (Build.VERSION.SDK_INT >= 36) {
                    setRequestPromotedOngoing(true)
                    colorOsLiveAlertChipText(left, right, case)?.let(::setShortCriticalText)
                }
            }
            .build()
        if (Build.VERSION.SDK_INT >= 36) {
            Log.d(
                TAG,
                "ColorOS Live Alert eligibility " +
                    "promotable=${notification.hasPromotableCharacteristics()} " +
                    "allowed=${manager.canPostPromotedNotifications()}",
            )
        }
        val serviceIntent = Intent(context, ColorOsLiveAlertService::class.java).putExtra(
            ColorOsLiveAlertService.EXTRA_NOTIFICATION,
            notification,
        ).putExtra(ColorOsLiveAlertService.EXTRA_DEVICE_ADDRESS, address)
        runCatching { context.startForegroundService(serviceIntent) }
            .onFailure {
                manager.notify(NOTIFICATION_ID, notification)
                Log.w(TAG, "Foreground keep-alive unavailable; posted notification directly", it)
            }
        Log.d(TAG, "ColorOS Live Alert posted")
    }

    private fun Intent.batteryParams(): BatteryParams {
        return getParcelableExtra("status", BatteryParams::class.java) ?: BatteryParams(
            left = batteryPod("left"),
            right = batteryPod("right"),
            case = batteryPod("case"),
        )
    }

    private fun Intent.batteryPod(prefix: String) =
        moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams(
            battery = getIntExtra("${prefix}_battery", 0),
            isCharging = getBooleanExtra("${prefix}_charging", false),
            isConnected = getBooleanExtra("${prefix}_connected", false),
        )

    private fun cancelLiveAlert(
        context: Context,
        manager: NotificationManager,
        statePrefs: android.content.SharedPreferences,
        eventElapsed: Long,
    ) {
        statePrefs.edit()
            .putLong(
                KEY_LAST_DISCONNECT_ELAPSED,
                eventElapsed.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
            )
            .remove(KEY_CURRENT_ADDRESS)
            .apply()
        context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val TAG = "HuaweiPods-LiveAlert"
        const val CHANNEL_ID = "HuaweiPodsLiveAlert"
        const val STATE_PREFS = "coloros_live_alert_state"
        const val KEY_LAST_DISCONNECT_ELAPSED = "last_disconnect_elapsed"
        const val KEY_CURRENT_ADDRESS = "current_address"
        const val NOTIFICATION_ID = ColorOsLiveAlertService.NOTIFICATION_ID
        const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        val TRUSTED_BLUETOOTH_ACTIONS = setOf(
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
        )
    }
}
