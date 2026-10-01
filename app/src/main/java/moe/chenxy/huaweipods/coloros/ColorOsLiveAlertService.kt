package moe.chenxy.huaweipods.coloros

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Keeps the app process alive only while a connected-headset Fluid Cloud is active. */
class ColorOsLiveAlertService : Service() {
    private var currentAddress: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()
    private var disconnectedChecks = 0
    private val bluetoothStateCheck = object : Runnable {
        override fun run() {
            val bluetoothEnabled = runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            }.getOrDefault(true)
            if (!bluetoothEnabled) {
                stopLiveAlert()
                return
            }
            // ColorOS 可能延迟或丢弃后台应用的断开广播；轮询音频连接状态兜底，连续两次断开才收起。
            disconnectedChecks = if (isCurrentDeviceDisconnected()) disconnectedChecks + 1 else 0
            if (disconnectedChecks >= 2) {
                Log.i(TAG, "Live Alert stopped: headset no longer connected")
                stopLiveAlert()
                return
            }
            mainHandler.postDelayed(this, BLUETOOTH_STATE_CHECK_INTERVAL_MS)
        }
    }

    private fun bindAudioProfiles() {
        val adapter = runCatching { getSystemService(BluetoothManager::class.java)?.adapter }
            .getOrNull() ?: return
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            runCatching {
                adapter.getProfileProxy(
                    this,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                            profileProxies[type] = proxy
                        }

                        override fun onServiceDisconnected(type: Int) {
                            profileProxies.remove(type)
                        }
                    },
                    profile,
                )
            }
        }
    }

    /** 两个音频 Profile 均已就绪且都不再连接当前耳机时才判定断开；状态未知时保持显示。 */
    private fun isCurrentDeviceDisconnected(): Boolean {
        val address = currentAddress ?: return false
        if (profileProxies.size < 2) return false
        return runCatching {
            val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
            profileProxies.values.all { proxy ->
                proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED
            }
        }.getOrDefault(false)
    }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            val shouldStop = when (event.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    event.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) in setOf(
                        BluetoothAdapter.STATE_TURNING_OFF,
                        BluetoothAdapter.STATE_OFF,
                    )

                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val disconnected = event.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED,
                    ) == BluetoothProfile.STATE_DISCONNECTED
                    val address = runCatching {
                        event.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )?.address
                    }.getOrNull()
                    disconnected && !address.isNullOrBlank() &&
                        address.equals(currentAddress, ignoreCase = true)
                }

                else -> false
            }
            if (shouldStop) stopLiveAlert()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            },
            Context.RECEIVER_EXPORTED,
        )
        bindAudioProfiles()
        mainHandler.post(bluetoothStateCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
            ?.takeIf(String::isNotBlank)
            ?: currentAddress
        val notification = intent?.getParcelableExtra(
            EXTRA_NOTIFICATION,
            Notification::class.java,
        ) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The independent package may receive a battery event before its first-run
            // Bluetooth permission prompt has been accepted. Keep the notification useful
            // without letting a rejected foreground-service type crash the whole app.
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(bluetoothStateCheck)
        runCatching { unregisterReceiver(bluetoothReceiver) }
        val adapter = runCatching { getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        profileProxies.forEach { (type, proxy) -> runCatching { adapter?.closeProfileProxy(type, proxy) } }
        profileProxies.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stopLiveAlert() {
        markColorOsLiveAlertDisconnected(this, android.os.SystemClock.elapsedRealtime())
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_NOTIFICATION = "coloros_live_alert_notification"
        const val EXTRA_DEVICE_ADDRESS = "coloros_live_alert_device_address"
        const val NOTIFICATION_ID = 10005
        const val BLUETOOTH_STATE_CHECK_INTERVAL_MS = 2_000L
        private const val TAG = "HuaweiPods-LiveAlert"
    }
}
