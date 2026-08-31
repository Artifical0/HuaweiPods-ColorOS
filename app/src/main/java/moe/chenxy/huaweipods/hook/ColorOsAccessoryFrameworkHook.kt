package moe.chenxy.huaweipods.hook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import java.util.WeakHashMap

internal fun shouldSuppressColorOsPopupClose(
    isHuaweiPodsFreeClipCard: Boolean,
    isUserClickInProgress: Boolean,
    automaticCloseAlreadySuppressed: Boolean,
): Boolean = isHuaweiPodsFreeClipCard &&
    !isUserClickInProgress &&
    !automaticCloseAlreadySuppressed

/** Keeps HuaweiPods' explicitly marked FreeClip card visible in ColorOS Quick Connect. */
object ColorOsAccessoryFrameworkHook : HookContext() {
    private const val DIALOG_ACTIVITY =
        "com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity"
    private const val USER_CLOSE_WINDOW_MS = 3_000L
    private val automaticCloseSuppressed = WeakHashMap<Any, Boolean>()
    private val lastUserTouchElapsed = WeakHashMap<Any, Long>()
    private val userClickDepth = ThreadLocal<Int>()
    private val registrationLock = Any()
    @Volatile
    private var receiverRegistered = false
    @Volatile
    private var accessoryContext: Context? = null
    @Volatile
    private var popupImageFileName: String? = null

    override fun onHook() {
        hookPopupImageResources()
        hookUserClicks()
        runCatching {
            val activityClass = findClass(DIALOG_ACTIVITY)
            runCatching {
                val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java).apply {
                    isAccessible = true
                }
                hookBefore(onCreate) {
                    val activity = instance ?: return@hookBefore
                    val card = readPopupCard(activity) ?: return@hookBefore
                    if (isHuaweiPodsFreeClipCard(card)) {
                        popupImageFileName =
                            ColorOsAccessoryPopupBridge.popupImageFileNameFromMarker(card.packageName)
                    }
                }
            }.onFailure {
                Log.w("HuaweiPods", "ColorOS Quick Connect image marker hook unavailable", it)
            }
            runCatching {
                val dispatchTouchEvent = activityClass.getMethod(
                    "dispatchTouchEvent",
                    MotionEvent::class.java,
                ).apply { isAccessible = true }
                hookBefore(dispatchTouchEvent) {
                    val activity = instance ?: return@hookBefore
                    val event = args.firstOrNull() as? MotionEvent ?: return@hookBefore
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        synchronized(lastUserTouchElapsed) {
                            lastUserTouchElapsed[activity] = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }.onFailure {
                Log.w("HuaweiPods", "ColorOS Quick Connect touch tracking unavailable", it)
            }
            val finishDialog = activityClass.getDeclaredMethod("h", activityClass).apply {
                isAccessible = true
            }
            hookBefore(finishDialog) {
                val activity = args.firstOrNull() ?: return@hookBefore
                if (!isHuaweiPodsFreeClipCard(readPopupCard(activity))) return@hookBefore
                val now = SystemClock.elapsedRealtime()
                val lastTouch = synchronized(lastUserTouchElapsed) {
                    lastUserTouchElapsed[activity]
                }
                val touchAge = lastTouch?.let { now - it }
                val isRecentUserTouch = touchAge != null && touchAge in 0..USER_CLOSE_WINDOW_MS
                val isUserClickInProgress = (userClickDepth.get() ?: 0) > 0 || isRecentUserTouch
                val wasAutomaticCloseAlreadySuppressed = synchronized(automaticCloseSuppressed) {
                    automaticCloseSuppressed[activity] == true
                }
                if (shouldSuppressColorOsPopupClose(
                        isHuaweiPodsFreeClipCard = true,
                        isUserClickInProgress = isUserClickInProgress,
                        automaticCloseAlreadySuppressed = wasAutomaticCloseAlreadySuppressed,
                    )
                ) {
                    synchronized(automaticCloseSuppressed) {
                        automaticCloseSuppressed[activity] = true
                    }
                    result = null
                    Log.i("HuaweiPods", "Suppressed ColorOS automatic FreeClip card close")
                } else if (isUserClickInProgress) {
                    synchronized(lastUserTouchElapsed) {
                        lastUserTouchElapsed.remove(activity)
                    }
                    Log.i(
                        "HuaweiPods",
                        "Allowed ColorOS FreeClip card close after user action (touchAgeMs=$touchAge)",
                    )
                }
            }
        }.onFailure {
            Log.w("HuaweiPods", "ColorOS Quick Connect close guard unavailable", it)
        }

        hookAfter(
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply {
                isAccessible = true
            },
        ) {
            (args[0] as? Context)?.let(::registerHostHandshake)
        }
    }

    /** Marks close callbacks reached synchronously from a real ColorOS view click. */
    private fun hookUserClicks() {
        val performClick = View::class.java.getDeclaredMethod("performClick").apply {
            isAccessible = true
        }
        hookBefore(performClick) {
            val view = instance as? View
            val activity = view?.context?.findActivity()
            val resourceName = view?.id?.takeIf { it != View.NO_ID }?.let { id ->
                runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            }
            if (
                activity?.javaClass?.name == DIALOG_ACTIVITY &&
                resourceName in setOf("button_single_big", "button_close") &&
                isHuaweiPodsFreeClipCard(readPopupCard(activity))
            ) {
                synchronized(lastUserTouchElapsed) {
                    lastUserTouchElapsed.remove(activity)
                }
                activity.finish()
                result = true
                Log.i("HuaweiPods", "Closed ColorOS FreeClip card from $resourceName")
                return@hookBefore
            }
            userClickDepth.set((userClickDepth.get() ?: 0) + 1)
        }
        hookAfter(performClick) {
            val nextDepth = (userClickDepth.get() ?: 1) - 1
            if (nextDepth <= 0) userClickDepth.remove() else userClickDepth.set(nextDepth)
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return current as? Activity
    }

    private data class PopupCard(val packageName: String?, val title: String?)

    private fun readPopupCard(activity: Any): PopupCard? = runCatching {
        val intent = activity.javaClass.getMethod("getIntent").invoke(activity) as? Intent
            ?: return@runCatching null
        val outer = intent.getBundleExtra("intent_bundle") ?: return@runCatching null
        val bytes = outer.getByteArray("base_view_data") ?: return@runCatching null
        val baseClass = findClass("fd.c")
        val base = baseClass.getMethod("parseFrom", ByteArray::class.java)
            .invoke(null, bytes)
        val plugin = baseClass.getMethod("getPluginViewData").invoke(base)
            ?: return@runCatching null
        val packageName = plugin.javaClass.getMethod("getPackageName").invoke(plugin) as? String
        val title = plugin.javaClass.getMethod("getDialogTitle").invoke(plugin) as? String
        PopupCard(packageName, title)
    }.onFailure {
        Log.w("HuaweiPods", "Unable to identify ColorOS Quick Connect card", it)
    }.getOrNull()

    private fun isHuaweiPodsFreeClipCard(card: PopupCard?): Boolean =
        card?.packageName?.let {
            it == BuildConfig.APPLICATION_ID || it.startsWith("${BuildConfig.APPLICATION_ID}|")
        } == true && card.title?.contains("FreeClip", ignoreCase = true) == true

    private fun registerHostHandshake(sourceContext: Context) {
        val context = sourceContext.applicationContext ?: sourceContext
        accessoryContext = context
        synchronized(registrationLock) {
            if (receiverRegistered) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action == HuaweiPodsAction.ACTION_COLOROS_POPUP_HOST_PROBE) {
                        sendReady(context)
                    }
                }
            }
            runCatching {
                context.registerReceiver(
                    receiver,
                    IntentFilter(HuaweiPodsAction.ACTION_COLOROS_POPUP_HOST_PROBE),
                    "android.permission.BLUETOOTH_PRIVILEGED",
                    null,
                    Context.RECEIVER_EXPORTED,
                )
            }.onSuccess {
                receiverRegistered = true
                sendReady(context)
            }.onFailure {
                Log.w("HuaweiPods", "ColorOS popup host handshake registration failed", it)
            }
        }
    }

    private fun sendReady(context: Context) {
        context.sendBroadcast(
            Intent(HuaweiPodsAction.ACTION_COLOROS_POPUP_HOST_READY).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
        )
    }

    private fun hookPopupImageResources() {
        listOf(
            runCatching {
                Resources::class.java.getDeclaredMethod(
                    "getDrawable",
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull(),
            runCatching {
                Resources::class.java.getDeclaredMethod(
                    "getDrawable",
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java,
                )
            }.getOrNull(),
        ).filterNotNull().forEach { method ->
            hookBefore(method) {
                val resourceId = args.firstOrNull() as? Int ?: return@hookBefore
                if (resourceId != ColorOsAccessoryPopupBridge.POPUP_IMAGE_DRAWABLE_ID) {
                    return@hookBefore
                }
                loadHuaweiPodsPopupDrawable(instance as? Resources)?.let { result = it }
            }
        }
    }

    private fun loadHuaweiPodsPopupDrawable(resources: Resources?): Drawable? {
        val context = accessoryContext ?: return null
        val targetResources = resources ?: context.resources
        val fileName = popupImageFileName
        if (fileName != null) {
            val cachedImage = runCatching {
                context.contentResolver.openInputStream(
                    Uri.Builder()
                        .scheme("content")
                        .authority("${BuildConfig.APPLICATION_ID}.podimages")
                        .appendPath(fileName)
                        .build(),
                )?.use(BitmapFactory::decodeStream)
            }.onFailure {
                Log.w("HuaweiPods", "ColorOS popup cached image unavailable; using bundled image", it)
            }.getOrNull()
            if (cachedImage != null) return BitmapDrawable(targetResources, cachedImage)
        }
        return runCatching {
            val moduleResources = context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            ).resources
            val bitmap = BitmapFactory.decodeResource(moduleResources, R.drawable.img_freeclip_box)
                ?: return@runCatching null
            BitmapDrawable(targetResources, bitmap)
        }.onFailure {
            Log.w("HuaweiPods", "ColorOS popup bundled original FreeClip image unavailable", it)
        }.getOrNull()
    }
}
