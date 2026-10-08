package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.displayName
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/**
 * ColorOS "My Devices" integration.
 *
 * - ColorOS 16.1（com.heytap.mydevices 17.4.15）：ViewBinding 详情页，复用 `row_bt_setting` 行。
 * - ColorOS 17（com.heytap.mydevices 17.25.10）：详情页改为 Preference 页面，复用
 *   `pref_bt_audio_settings`（打开系统蓝牙设备设置）这一项。
 *
 * 两代页面都只改写系统已有的入口，不新增依赖 COUI 私有资源的 View。17.x 的 androidx 类成员
 * 均被混淆，因此按类型结构定位绑定方法，并通过 `android.R.id.title/summary` 修改文字。
 * 所有反射都有兜底，未知版本保持系统原样。
 */
object ColorOsMyDevicesHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsMyDevices"
    private const val LEGACY_DETAIL_FRAGMENT =
        "com.oplus.mydevices.bluetooth.fragment.BtDetailPageFragment"
    private const val PREFERENCE_DETAIL_FRAGMENT =
        "com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment"
    private const val PREFERENCE_CLASS = "androidx.preference.Preference"
    private const val SETTINGS_PREFERENCE_KEY = "pref_bt_audio_settings"
    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    private val bluetoothAddressPattern = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    private var statusReceiverContext: Context? = null
    private var statusReceiver: BroadcastReceiver? = null
    private val activeRows = WeakHashMap<View, ColorOsHeadsetRow>()
    private val preferenceRows = WeakHashMap<Any, ColorOsHeadsetRow>()
    private val latestSummaries = ConcurrentHashMap<String, String>()
    private val latestAncModes = ConcurrentHashMap<String, NoiseControlMode>()
    private val hookedBindMethods = ConcurrentHashMap.newKeySet<Method>()

    override fun onHook() {
        val legacy = runCatching { hookLegacyDetailPage() }
        val preference = runCatching { hookPreferenceDetailPage() }
        if (legacy.isSuccess) Log.i(TAG, "My Devices 16.x detail hook installed")
        if (preference.isSuccess) Log.i(TAG, "My Devices 17.x preference detail hook installed")
        if (legacy.isFailure && preference.isFailure) {
            Log.w(
                TAG,
                "Unsupported My Devices build; leaving the stock page unchanged",
                preference.exceptionOrNull() ?: legacy.exceptionOrNull()!!,
            )
        }
    }

    override fun onClose() {
        hookedBindMethods.clear()
        val receiver = statusReceiver
        val context = statusReceiverContext
        if (receiver != null && context != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        statusReceiver = null
        statusReceiverContext = null
        synchronized(activeRows) { activeRows.clear() }
        synchronized(preferenceRows) { preferenceRows.clear() }
        latestSummaries.clear()
        latestAncModes.clear()
    }

    private fun hookLegacyDetailPage() {
        val method = findMethod(
            LEGACY_DETAIL_FRAGMENT,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        )
        hookAfter(method) {
            val fragment = instance ?: return@hookAfter
            val root = args.firstOrNull() as? View ?: return@hookAfter
            runCatching { attachLegacyEntry(fragment, root) }
                .onFailure { Log.w(TAG, "Unable to attach HuaweiPods entry", it) }
        }
    }

    private fun hookPreferenceDetailPage() {
        val bindMethods = preferenceBindMethods(findClass(PREFERENCE_CLASS))
        check(bindMethods.isNotEmpty()) { "Preference bind method not found" }
        val onViewCreated = findMethod(
            PREFERENCE_DETAIL_FRAGMENT,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        )
        hookAfter(onViewCreated) {
            val fragment = instance ?: return@hookAfter
            val root = args.firstOrNull() as? View ?: return@hookAfter
            runCatching { attachPreferenceEntry(fragment, root) }
                .onFailure { Log.w(TAG, "Unable to attach HuaweiPods preference entry", it) }
        }
        bindMethods.forEach(::hookPreferenceBind)
    }

    /**
     * COUIPreference 等子类会重写绑定方法并在 super 之后继续设置标题/摘要。沿目标 Preference 的
     * 类链逐层 Hook，最外层重写的 after 回调最后执行，保证入口文字不被子类覆盖。
     */
    private fun hookPreferenceBindChain(preferenceClass: Class<*>) {
        var type: Class<*>? = preferenceClass
        while (type != null && type != Any::class.java) {
            preferenceBindMethods(type).forEach(::hookPreferenceBind)
            if (type.name == PREFERENCE_CLASS) break
            type = type.superclass
        }
    }

    private fun hookPreferenceBind(method: Method) {
        if (!hookedBindMethods.add(method)) return
        hookAfter(method) {
            val preference = instance ?: return@hookAfter
            val holder = args.firstOrNull() ?: return@hookAfter
            runCatching { onPreferenceBound(preference, holder) }
                .onFailure { Log.w(TAG, "Unable to update bound HuaweiPods entry", it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachLegacyEntry(fragment: Any, root: View) {
        val address = getObjectField(fragment, "mMac") as? String
        val viewModel = getObjectField(fragment, "viewModel")
        val device = bluetoothDevice(address)
        val deviceName = runCatching { callMethod(viewModel, "u") as? String }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatching { device?.name ?: device?.alias }.getOrNull()
        val route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported || device == null) return

        val row = legacySettingsRow(fragment, root) ?: run {
            Log.w(TAG, "Huawei device found but row_bt_setting is unavailable")
            return
        }
        val entry = ColorOsHeadsetRow(
            address = device.address.uppercase(),
            deviceName = route.displayName,
            route = route,
        )
        synchronized(activeRows) { activeRows[row] = entry }
        customizeRow(row, entry, latestSummaries[entry.address])
        row.setOnClickListener {
            launchHuaweiPods(row.context, device)
        }
        registerStatusReceiver(row.context)
        requestCurrentStatus(row.context)
        Log.i(TAG, "Attached entry for ${route.displayName}")
    }

    @SuppressLint("MissingPermission")
    private fun attachPreferenceEntry(fragment: Any, root: View) {
        val address = preferenceFragmentAddress(fragment) ?: return
        val device = bluetoothDevice(address) ?: return
        val deviceName = runCatching { device.alias ?: device.name }.getOrNull()
        val route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported) return

        val preference = runCatching {
            callMethod(fragment, "findPreference", SETTINGS_PREFERENCE_KEY)
        }.getOrNull() ?: run {
            Log.w(TAG, "Huawei device found but $SETTINGS_PREFERENCE_KEY is unavailable")
            return
        }
        runCatching { hookPreferenceBindChain(preference.javaClass) }
            .onFailure { Log.w(TAG, "Unable to hook ${preference.javaClass.name} binding", it) }
        synchronized(preferenceRows) {
            preferenceRows[preference] = ColorOsHeadsetRow(
                address = device.address.uppercase(),
                deviceName = route.displayName,
                route = route,
            )
        }
        registerStatusReceiver(root.context)
        requestCurrentStatus(root.context)
        Log.i(TAG, "Attached preference entry for ${route.displayName}")
    }

    private fun onPreferenceBound(preference: Any, holder: Any) {
        val itemView = getObjectField(holder, "itemView") as? View ?: return
        val entry = synchronized(preferenceRows) { preferenceRows[preference] }
        if (entry == null) {
            // RecyclerView 复用了原来承载 HuaweiPods 入口的 View；系统绑定已恢复其文字和点击，
            // 只需移除注入的降噪切换。
            synchronized(activeRows) { activeRows.remove(itemView) }
            removeAncControls(itemView)
            return
        }
        synchronized(activeRows) { activeRows[itemView] = entry }
        customizeRow(itemView, entry, latestSummaries[entry.address])
        val device = bluetoothDevice(entry.address) ?: return
        itemView.setOnClickListener { launchHuaweiPods(it.context, device) }
    }

    private fun legacySettingsRow(fragment: Any, root: View): LinearLayout? {
        val bindingRow = runCatching {
            val binding = getObjectField(fragment, "_binding")
            getObjectField(binding, "h") as? LinearLayout
        }.getOrNull()
        if (bindingRow != null) return bindingRow

        val rowId = root.resources.getIdentifier("row_bt_setting", "id", packageName)
        return rowId.takeIf { it != 0 }?.let(root::findViewById)
    }

    /** 17.x 详情页只有一个保存 MAC 的 String 字段；按取值校验，不依赖混淆后的字段名。 */
    private fun preferenceFragmentAddress(fragment: Any): String? {
        var type: Class<*>? = fragment.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields
                .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(fragment) as? String
                    }.getOrNull()
                    if (value != null && bluetoothAddressPattern.matches(value)) return value
                }
            type = type.superclass
        }
        return null
    }

    private fun customizeRow(row: View, entry: ColorOsHeadsetRow, summary: String? = null) {
        customizeRowText(row, entry.deviceName, summary)
        runCatching { attachAncControls(row, entry) }
            .onFailure { Log.w(TAG, "Unable to attach noise control", it) }
    }

    private fun customizeRowText(row: View, deviceName: String, summary: String? = null) {
        row.isEnabled = true
        row.alpha = 1f
        row.contentDescription = "HuaweiPods, $deviceName"
        val summaryText = summary ?: if (
            Locale.getDefault().language == Locale.CHINESE.language
        ) {
            "$deviceName 电量与设置"
        } else {
            "$deviceName battery and settings"
        }
        val title = row.findViewById<TextView>(android.R.id.title)
        val summaryView = row.findViewById<TextView>(android.R.id.summary)
        if (title != null) {
            title.text = "HuaweiPods"
            summaryView?.apply {
                text = summaryText
                visibility = View.VISIBLE
            }
            return
        }
        val labels = collectTextViews(row)
        when (labels.size) {
            0 -> Unit
            1 -> labels[0].text = "HuaweiPods · $deviceName"
            else -> {
                labels[0].text = "HuaweiPods"
                labels[1].text = summaryText
            }
        }
    }

    private fun registerStatusReceiver(context: Context) {
        if (statusReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val received = intent ?: return
                val action = HuaweiPodsAction.canonical(received.action)
                if (action == HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
                    val address = received.getStringExtra("address")?.uppercase() ?: return
                    val mode = NoiseControlMode.fromBroadcastStatus(received.getIntExtra("status", 0))
                    if (!mode.isKnownMode()) return
                    latestAncModes[address] = mode
                    synchronized(activeRows) { activeRows.entries.toList() }
                        .filter { (row, entry) -> row.isAttachedToWindow && entry.address == address }
                        .forEach { (row, entry) -> updateAncControls(row, entry) }
                    return
                }
                if (action != HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) return
                val address = received.getStringExtra("address")?.uppercase() ?: return
                val summary = colorOsBatterySummary(
                    leftBattery = received.getIntExtra("left_battery", 0),
                    leftConnected = received.getBooleanExtra("left_connected", false),
                    rightBattery = received.getIntExtra("right_battery", 0),
                    rightConnected = received.getBooleanExtra("right_connected", false),
                    caseBattery = received.getIntExtra("case_battery", 0),
                    caseConnected = received.getBooleanExtra("case_connected", false),
                    chinese = Locale.getDefault().language == Locale.CHINESE.language,
                ) ?: return
                latestSummaries[address] = summary
                synchronized(activeRows) { activeRows.entries.toList() }
                    .filter { (row, entry) -> row.isAttachedToWindow && entry.address == address }
                    .forEach { (row, entry) -> customizeRow(row, entry, summary) }
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_ANC_CHANGED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            statusReceiver = receiver
            statusReceiverContext = appContext
        }.onFailure {
            Log.w(TAG, "Unable to register My Devices battery receiver", it)
        }
    }

    private fun requestCurrentStatus(context: Context) {
        runCatching {
            // 蓝牙进程按 sentFromPackage 校验控制广播，必须共享发送方身份。
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure {
            Log.w(TAG, "Unable to request current headset status", it)
        }
    }

    /**
     * 在入口摘要下方加入降噪模式切换（关闭 / 降噪 / 通透）。ColorOS 的三方耳机详情页没有降噪控件，
     * 这里在已接管的入口行内提供，与 AirPods 页面的三段式切换对应。摘要所在容器不是纵向
     * LinearLayout 时不注入，保持系统原样。
     */
    private fun attachAncControls(row: View, entry: ColorOsHeadsetRow) {
        val options = colorOsNoiseControlOptions(entry.route)
        if (options.isEmpty()) return
        val summary = row.findViewById<TextView>(android.R.id.summary)
            ?: collectTextViews(row).getOrNull(1)
            ?: return
        val container = summary.parent as? LinearLayout ?: return
        if (container.orientation != LinearLayout.VERTICAL) return
        val existing = container.findViewWithTag<LinearLayout>(ANC_CONTROLS_TAG)
        val controls = existing ?: LinearLayout(row.context).apply {
            tag = ANC_CONTROLS_TAG
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = row.dp(8) }
            container.addView(this)
        }
        if (controls.childCount != options.size) {
            controls.removeAllViews()
            options.forEachIndexed { index, mode ->
                controls.addView(ancChip(row.context, mode, entry), LinearLayout.LayoutParams(
                    0,
                    row.dp(32),
                    1f,
                ).apply { if (index > 0) marginStart = row.dp(8) })
            }
        }
        updateAncControls(row, entry)
    }

    private fun ancChip(context: Context, mode: NoiseControlMode, entry: ColorOsHeadsetRow): TextView =
        TextView(context).apply {
            tag = mode
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = ancLabel(mode)
            isClickable = true
            setOnClickListener { view ->
                val device = bluetoothDevice(entry.address) ?: return@setOnClickListener
                latestAncModes[entry.address] = mode
                (view.parent?.parent as? View)?.let { updateAncControls(it, entry) }
                requestAncMode(view.context, device, mode)
            }
        }

    private fun updateAncControls(row: View, entry: ColorOsHeadsetRow) {
        val controls = row.findViewWithTag<LinearLayout>(ANC_CONTROLS_TAG) ?: return
        val selected = latestAncModes[entry.address]
        val accent = row.context.themeColor(android.R.attr.colorAccent, Color.rgb(0x2A, 0x6D, 0xF4))
        val normalText = row.context.themeColor(android.R.attr.textColorPrimary, Color.DKGRAY)
        repeat(controls.childCount) { index ->
            val chip = controls.getChildAt(index) as? TextView ?: return@repeat
            val active = chip.tag == selected
            chip.background = GradientDrawable().apply {
                cornerRadius = row.dp(16).toFloat()
                if (active) {
                    setColor(accent)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(row.dp(1), ColorStateList.valueOf(normalText).withAlpha(0x40))
                }
            }
            chip.setTextColor(if (active) Color.WHITE else normalText)
            chip.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.isSelected = active
        }
    }

    private fun removeAncControls(itemView: View) {
        val controls = itemView.findViewWithTag<View>(ANC_CONTROLS_TAG) ?: return
        (controls.parent as? ViewGroup)?.removeView(controls)
    }

    private fun requestAncMode(context: Context, device: BluetoothDevice, mode: NoiseControlMode) {
        runCatching {
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                setPackage(BLUETOOTH_PACKAGE)
                putExtra("address", device.address)
                putExtra("status", mode.broadcastStatus)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.i(TAG, "Noise control requested mode=$mode")
        }.onFailure {
            Log.w(TAG, "Unable to request noise control mode=$mode", it)
        }
    }

    private fun ancLabel(mode: NoiseControlMode): String {
        val chinese = Locale.getDefault().language == Locale.CHINESE.language
        return when (mode) {
            NoiseControlMode.OFF -> if (chinese) "关闭" else "Off"
            NoiseControlMode.NOISE_CANCELLATION -> if (chinese) "降噪" else "Noise cancelling"
            NoiseControlMode.TRANSPARENCY -> if (chinese) "通透" else "Awareness"
            NoiseControlMode.UNKNOWN -> ""
        }
    }

    private fun NoiseControlMode.isKnownMode(): Boolean = this != NoiseControlMode.UNKNOWN

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Context.themeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        if (!theme.resolveAttribute(attr, typedValue, true)) return fallback
        return if (typedValue.resourceId != 0) {
            runCatching { getColor(typedValue.resourceId) }.getOrDefault(fallback)
        } else {
            typedValue.data
        }
    }

    private fun collectTextViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> addAll(collectTextViews(view.getChildAt(index))) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDevice(address: String?): BluetoothDevice? {
        if (address.isNullOrBlank()) return null
        return runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
    }

    private fun launchHuaweiPods(context: Context, device: BluetoothDevice) {
        val popupIntent = Intent(HuaweiPodsAction.ACTION_SHOW_PODS_UI).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(popupIntent) }
            .onFailure { error ->
                Log.w(TAG, "Popup unavailable; opening the module activity", error)
                runCatching {
                    context.startActivity(Intent().apply {
                        setClassName(
                            BuildConfig.APPLICATION_ID,
                            "moe.chenxy.huaweipods.MainActivity",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { Log.e(TAG, "Unable to open HuaweiPods", it) }
            }
    }
}

/**
 * androidx `Preference.onBindViewHolder(PreferenceViewHolder)` 在厂商应用里会被 R8 改名；
 * 它是 Preference 上唯一接收 RecyclerView.ViewHolder 子类的实例方法，按结构定位。
 */
internal fun preferenceBindMethods(preferenceClass: Class<*>): List<Method> =
    preferenceClass.declaredMethods.filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 1 &&
            isRecyclerViewHolderType(method.parameterTypes[0])
    }.onEach { it.isAccessible = true }

private fun isRecyclerViewHolderType(type: Class<*>): Boolean {
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        if (
            current.name.startsWith("androidx.recyclerview.widget.RecyclerView$") &&
            current.declaredFields.any { field ->
                field.name == "itemView" && View::class.java.isAssignableFrom(field.type)
            }
        ) {
            return true
        }
        current = current.superclass
    }
    return false
}

private data class ColorOsHeadsetRow(
    val address: String,
    val deviceName: String,
    val route: HuaweiDeviceRoute,
)

private const val ANC_CONTROLS_TAG = "huaweipods_noise_control"

internal fun colorOsBatterySummary(
    leftBattery: Int,
    leftConnected: Boolean,
    rightBattery: Int,
    rightConnected: Boolean,
    caseBattery: Int,
    caseConnected: Boolean,
    chinese: Boolean,
): String? {
    val parts = buildList {
        if (leftConnected) add(if (chinese) "左 ${leftBattery.coerceIn(0, 100)}%" else "L ${leftBattery.coerceIn(0, 100)}%")
        if (rightConnected) add(if (chinese) "右 ${rightBattery.coerceIn(0, 100)}%" else "R ${rightBattery.coerceIn(0, 100)}%")
        if (caseConnected) add(if (chinese) "盒 ${caseBattery.coerceIn(0, 100)}%" else "Case ${caseBattery.coerceIn(0, 100)}%")
    }
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}
