package moe.chenxy.huaweipods.hook

import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import dalvik.system.PathClassLoader
import moe.chenxy.huaweipods.BuildConfig

/**
 * Calls ColorOS' own PantaConnect discovery-dialog renderer.
 *
 * The public external API is restricted to an OPPO e-badge allowlist on ColorOS 16. The
 * plugin API is the same entry used by accessory plugins. Keep every vendor class behind
 * reflection so the module remains installable on non-ColorOS ROMs.
 */
internal object ColorOsAccessoryPopupBridge {
    private const val ACCESSORY_PACKAGE = "com.heytap.accessory"
    private const val SCENE_SERVICE =
        "com.oplus.pantaconnect.pluginmgr.services.SceneService"
    private const val ACTION_DIALOG =
        "com.heytap.accessory.plugin.discovery.action.DIALOG"
    private const val DIALOG_PARAMS =
        "com.heytap.accessory.plugin.discovery.DialogParams"
    private const val DRAWABLE_DESCRIPTION =
        "com.heytap.accessory.plugin.discovery.DialogParams\$DrawableDes"
    private val SAFE_IMAGE_FILE_NAME = Regex("[A-Za-z0-9._-]+")
    internal const val POPUP_IMAGE_DRAWABLE_ID = 0x7f0e7a11

    // ColorOS treats 1/2 as updates to an already visible card and filters them when there
    // is no active card. A new connection therefore has to open state 0 first.
    private const val DIALOG_TYPE_NEW_CONNECTION = 0
    private const val DIALOG_TYPE_CONNECTED_UPDATE = 2
    private const val SDK_VERSION = 3
    // State 0 cards are discovery cards and ColorOS closes them as soon as their address is
    // already connected. Use Android's conventional anonymous Bluetooth placeholder for the
    // presentation session; the real device remains the source of the displayed data.
    private const val PRESENTATION_DEVICE_ADDRESS = "02:00:00:00:00:00"
    fun showConnected(
        context: Context,
        deviceName: String,
        batteryText: String,
        imageFileName: String?,
    ): Boolean = runCatching {
        val classLoader = accessoryClassLoader(context)
        val paramsClass = Class.forName(DIALOG_PARAMS, true, classLoader)
        val params = paramsClass.getDeclaredConstructor().newInstance()

        fun setString(method: String, value: String) {
            paramsClass.getMethod(method, String::class.java).invoke(params, value)
        }

        // Marker consumed by ColorOsAccessoryFrameworkHook. The vendor renderer safely falls
        // back to its host resources when this package is not an installed PantaConnect plugin.
        setString("setPackageName", popupMarkerPackageName(imageFileName))
        setString("setTitle", deviceName)
        setString("setContinueButton", "完成")
        setString("setNotificationTile", deviceName)
        setString("setNotificationContent", batteryText)
        paramsClass.getMethod("setHideCancelButton", Boolean::class.javaPrimitiveType)
            .invoke(params, true)

        val drawableClass = Class.forName(DRAWABLE_DESCRIPTION, true, classLoader)
        val drawable = drawableClass.getDeclaredConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).newInstance(batteryText, POPUP_IMAGE_DRAWABLE_ID, "")
        paramsClass.getMethod("addDrawableDes", drawableClass).invoke(params, drawable)

        val presentationDevice = checkNotNull(BluetoothAdapter.getDefaultAdapter())
            .getRemoteDevice(PRESENTATION_DEVICE_ADDRESS)
        fun dialogIntent(type: Int) = Intent(ACTION_DIALOG).apply {
            component = ComponentName(ACCESSORY_PACKAGE, SCENE_SERVICE)
            putExtra("dialog_type", type)
            putExtra("sdk_version", SDK_VERSION)
            putExtra("bluetooth_device", presentationDevice)
            putExtra("dialog_params", params as Parcelable)
        }
        checkNotNull(context.startService(dialogIntent(DIALOG_TYPE_NEW_CONNECTION))) {
            "ColorOS SceneService did not accept the popup request"
        }
        // The vendor API models a popup as 0 (open) followed by 2 (connected). Sending only
        // state 0 makes ColorOS close the card when it observes that audio is already routed.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                context.startService(dialogIntent(DIALOG_TYPE_CONNECTED_UPDATE))
            }.onFailure {
                Log.w("HuaweiPods", "ColorOS connected-card update failed", it)
            }
        }, 120L)
        true
    }.onFailure {
        Log.w("HuaweiPods", "ColorOS official accessory popup unavailable", it)
    }.getOrDefault(false)

    private fun accessoryClassLoader(context: Context): ClassLoader {
        runCatching {
            return context.createPackageContext(
                ACCESSORY_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            ).classLoader
        }

        val appInfo = context.packageManager.getApplicationInfo(
            ACCESSORY_PACKAGE,
            ApplicationInfoFlags,
        )
        val dexPath = buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let(::addAll)
        }.joinToString(":")
        return PathClassLoader(dexPath, context.classLoader)
    }

    @Suppress("DEPRECATION")
    private val ApplicationInfoFlags: Int
        get() = 0

    internal fun popupMarkerPackageName(imageFileName: String?): String = imageFileName
        ?.takeIf(SAFE_IMAGE_FILE_NAME::matches)
        ?.let { "${BuildConfig.APPLICATION_ID}|$it" }
        ?: BuildConfig.APPLICATION_ID

    internal fun popupImageFileNameFromMarker(packageName: String?): String? = packageName
        ?.takeIf { it.startsWith("${BuildConfig.APPLICATION_ID}|") }
        ?.substringAfter('|')
        ?.takeIf(SAFE_IMAGE_FILE_NAME::matches)
}
