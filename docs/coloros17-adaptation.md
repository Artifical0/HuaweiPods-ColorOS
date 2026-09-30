# ColorOS 17 适配核对

## 样本

- 全量包：OnePlus PLK110 `PLK110_17.0.0.102(CN01)`，Android 17（SDK 37），`CP2A.260605.016`
- 提取分区：`system`、`system_ext`、`my_product`、`my_stock`
- 宿主版本：

| 包名 | 版本 | 位置 |
| --- | --- | --- |
| `com.android.bluetooth` | Android 17 | `system/app/Bluetooth`（非 Mainline APEX） |
| `com.heytap.mydevices` | 17.25.10 | `my_stock/priv-app/MyDevices` |
| `com.heytap.accessory` | 17.6.15 | `my_stock/priv-app/AccessoryFramework` |
| `com.oplus.melody` | 17.5.1 | `my_stock/del-app/Melody` |
| `com.oplus.wirelesssettings` | ColorOS 17 | `system_ext/priv-app/WirelessSettings` |
| SystemUI | ColorOS 17 | `system_ext/priv-app/SystemUI` |

核对方式：dexdump 导出方法/字段签名逐项比对，关键逻辑用 jadx 反编译确认。

## 现有 Hook 核对

| Hook 点 | ColorOS 17 | 处理 |
| --- | --- | --- |
| `A2dpService.handleConnectionStateChanged(BluetoothDevice,int,int)`、构造器、`mHandler`、`getConnectedDevices()` | 一致；父类改为 `profile.ConnectableProfile → profile.ProfileService`，仍是 `ContextWrapper` | 不变 |
| `HeadsetStateMachine.processUnknownAt(String,BluetoothDevice)`、`mNativeInterface`、`mDevice`、`mHeadsetService`、`mAdapterService` | 一致 | 不变 |
| `HeadsetNativeInterface.atResponseString/atResponseCode` | 一致 | 不变 |
| `AdapterService.getAdapterService()` 静态方法 | **已移除** | 通过 `ProfileService.getAdapterService()` 获取 |
| 状态栏 `wireless_headset` 图标 | 蓝牙 UID 无 `STATUS_BAR` 权限 | 首次被拒后停止上游的图标保活 |
| SystemUI 流体云 | `OplusLiveAlertNotificationsRepository`：`isPromotedOngoing()` 或 OPLUS live alert 配置即交给 Seedling 插件 | 现有 promoted ongoing 通知不变 |
| 我的设备详情页 | `com.oplus.mydevices.bluetooth.fragment.BtDetailPageFragment` → **`com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment`**，改为 Preference 页面，`mMac`/`viewModel`/`_binding` 均不存在 | 新增 Preference 路径：按唯一 MAC 字段取地址，改写 `pref_bt_audio_settings`；按参数类型定位被混淆的 `Preference.onBindViewHolder` |
| 快速设备连接 `DialogActivity` 自动关闭 | 静态 `h(DialogActivity)` → **`g(DialogActivity)`** | 按签名 `static void (DialogActivity)` 定位 |
| 快速设备连接卡片数据 | 混淆的 `fd.c` → **`com.oplus.pantaconnect.data.BaseViewData`**，插件数据 `getPackageName/getDialogTitle` 不变 | 先新类名后旧类名 |
| `SceneService` + `com.heytap.accessory.plugin.discovery.action.DIALOG`、`DialogParams` | 一致，服务需 `com.oplus.permission.safe.CONNECTIVITY`（蓝牙进程具备） | 不变 |
| 设置 `Settings$BluetoothDeviceDetailActivity` | 导出，需 `BLUETOOTH_CONNECT` | 不变 |
| 电池 `StartupAppListActivity` | 需 `oplus.permission.OPLUS_COMPONENT_SAFE`，三方应用无法直接打开 | 维持应用详情页兜底 |

## ColorOS 的三方耳机能力框架

ColorOS 17 蓝牙栈内置了面向 AirPods 与合作三方耳机的扩展能力框架（`oplus_bluetooth_common_ext.jar`，由 `OplusBTFactory` 在运行时加载，应用类加载器中可通过 `OplusFeatureCache.get(IOplusRemoteDevice.DEFAULT)` 访问）：

- 设备属性：协议通道（`TRANSPORT_3RD_ECHO`=0、`TRANSPORT_HFP_AT`=1、`TRANSPORT_AIRPOD`=2）、厂商 ID / 产品 ID、特性掩码（电量、降噪、查找响铃、佩戴、低时延、低电量提醒）。
- 电量：`notifyBatteryLevelChanged(device, int[6])`，数组为左、右、盒电量与三者充电状态（1 充电、2 未充电、4 断开，-1 未知），同时写入 Android 标准电量（左右较低值）。耳机未入盒时对外隐藏盒电量。
- 佩戴：`notifyWearStatusChanged(device, left, right)`，0 佩戴、1 未佩戴、2 入盒、255 未知。
- 降噪：`setOplusDeviceNoiseModeSupport`、`notifyNoiseModeChanged`；系统界面通过 `setExtendFeatureStatus` 下发控制，HFP AT 通道最终进入 `OplusThirdPartyEarbudAtHandler`（`+OBUDS` 协议）。
- 消费方：蓝牙设置（`getBatteryInfo` 显示左右耳电量，需厂商 ID 在 `persist.bluetooth.3rd_earbuds_hfp_at_vendor` 白名单，默认为 5 家合作厂商）、我的设备（`OplusBluetoothAdapterWrapper`：电量、特性掩码、降噪状态读写、回调注册）、快速设备连接（`HeadSetConNetworking` 与三方耳机弹窗、按厂商/产品 ID 的云端资源）。

## 本版接入

`ColorOsSystemBatteryBridge` 在蓝牙进程中为华为耳机：

1. 设置协议通道为 `TRANSPORT_HFP_AT`，使 `getBatteryInfo` 返回左右耳 / 盒电量；
2. 按充电状态推导入盒（2）或佩戴（0），让系统在入盒时显示盒电量；
3. 调用 `notifyBatteryLevelChanged`，并用在线耳机较低值校正 Android 标准电量；
4. 会话断开时清除标准电量，OPLUS 属性由蓝牙栈在 ACL 断开时自行重置。

**不声明任何可控特性**，特性掩码保持 0：系统不会尝试用 OBUDS AT 指令控制华为耳机，也不会触发三方耳机的回连、共享与查找逻辑。框架缺失时（如 ColorOS 16）只写入 Android 标准电量。

## 后续：原生降噪控制与三方耳机卡片

要让“我的设备”和控制中心像 OPPO 耳机一样直接切换降噪，需要把华为耳机完整登记为三方耳机。已确认的接入点：

- 设置厂商 / 产品 ID（`setOplusDeviceVendorAndProduct`），产品 ID 第 20～23 位为 1～3 时视为 TWS；
- 声明特性与降噪模式（`setOplusDeviceFeatures`、`setOplusDeviceNoiseModeSupport`），状态变化时 `notifyNoiseModeChanged`；
- Hook `OplusThirdPartyEarbudAtHandler.setExtendFeatureStatus` 与 `onHfpConnected`：华为设备不发 `+OBUDS` 握手，把系统下发的降噪 / 低时延指令翻译成华为协议；
- 厂商白名单：蓝牙设置与 AT 处理器读取 `persist.bluetooth.3rd_earbuds_hfp_at_vendor`，我的设备与快速设备连接另有云端支持列表，需要逐一 Hook 或补充。
- 我的设备已内置 AirPods 插件（`com.heytap.mydevices.plugin.airpods.core.AirpodsBluetoothManager`，含降噪模式切换与佩戴状态）和三方耳机扩展（特性开关 `oplus.software.bt.3rd_earbuds_extend`，按厂商 ID 获取资源的 `getThirdPartyHeadsetResUri`），是原生降噪卡片的现成载体。

这一步会改变多个系统应用对设备的判定，必须实机逐项验证（降噪按钮、佩戴状态、弹窗资源、回连与音频共享），因此本版未启用。
