package com.phenix.xglobal.ctx.android

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.phenix.xglobal.ctx.core.IGlobalCapability
import com.phenix.xglobal.ctx.core.IGlobalContext
import com.phenix.xglobal.ctx.core.StateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.util.TimeZone

/** 网络类型。 */
public enum class NetworkType { WIFI, CELLULAR, NONE }

/** 屏幕状态。 */
public enum class ScreenState { ON, OFF, LOCKED }

/**
 * 内置能力：网络状态（网络类型 + 信号格数）。
 * 构造后由使用方 register 激活。
 * 信号格数（bars 0..4）：
 * - 蜂窝走 TelephonyManager 信号监听（模拟器 Extended Controls → Cellular → Signal strength 滑块可实时驱动）
 * - WiFi/其他走 NetworkCapabilities.signalStrength（系统 -99..99 编码，由 onCapabilitiesChanged 推送，无位置权限问题）
 * 无网络为 0。全程无轮询线程。
 */
public class NetworkCapability(
    private val context: IGlobalContext,
    private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.network"

    public object TypeKey : StateKey<NetworkType>("xglobal.network.type", NetworkType.NONE)
    public object BarsKey : StateKey<Int>("xglobal.network.bars", 0)

    public val current: NetworkType get() = context.store.get(TypeKey)
    public val currentBars: Int get() = context.store.get(BarsKey)
    public val flow: StateFlow<NetworkType> get() = context.store.flow(TypeKey)
    public val barsFlow: StateFlow<Int> get() = context.store.flow(BarsKey)

    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // signalStrength 为系统编码的无量纲值,越高越好;WiFi 场景直接归一到 0..4 格
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                context.store.set(BarsKey, caps.signalStrength.coerceIn(0, 4))
            }
            refresh()
        }
    }

    // API < 31 蜂窝信号监听（API 31+ 走 TelephonyCallback）
    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            context.store.set(BarsKey, signalStrength.level)
        }
    }

    private var telephonyCallback: Any? = null

    override fun onAttach(context: IGlobalContext) {
        refresh()
        // 防御性注册：个别 ROM/模拟器对监听注册抛 SecurityException 等，不让它击穿调用方
        runCatching {
            connectivityManager()?.registerNetworkCallback(
                NetworkRequest.Builder().build(), callback
            )
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        context.store.set(BarsKey, signalStrength.level)
                    }
                }
                telephonyCallback = cb
                telephonyManager?.registerTelephonyCallback(appContext.mainExecutor, cb)
            } else {
                telephonyManager?.listen(legacyListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            }
        }
    }

    override fun onDetach() {
        runCatching { connectivityManager()?.unregisterNetworkCallback(callback) }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    override fun onConfigurationChanged() { refresh() }

    private fun refresh() {
        val cm = connectivityManager()
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.NONE
        }
        context.store.set(TypeKey, type)
        // 初始格数：蜂窝从 TelephonyManager 读；WiFi 等 onCapabilitiesChanged 推送
        if (type == NetworkType.CELLULAR) {
            context.store.set(BarsKey, telephonyManager?.signalStrength?.level ?: 0)
        } else if (type == NetworkType.NONE) {
            context.store.set(BarsKey, 0)
        }
    }

    private fun connectivityManager(): ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}

/**
 * 内置能力：屏幕状态（亮灭/锁屏，懒加载）。
 * 通过 SCREEN_ON/OFF、USER_PRESENT 等广播驱动刷新。
 */
public class ScreenCapability(
    private val context: IGlobalContext,
    private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.screen"

    public object StateKeyToken : StateKey<ScreenState>("xglobal.screen.state", ScreenState.OFF)

    public val current: ScreenState get() = context.store.get(StateKeyToken)
    public val flow: StateFlow<ScreenState> get() = context.store.flow(StateKeyToken)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> refresh()
                Intent.ACTION_USER_PRESENT -> context.store.set(StateKeyToken, ScreenState.ON)
                Intent.ACTION_SCREEN_OFF -> context.store.set(StateKeyToken, ScreenState.OFF)
            }
        }
    }

    override fun onAttach(context: IGlobalContext) {
        refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    override fun onDetach() {
        appContext.unregisterReceiver(receiver)
    }

    private fun refresh() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val state = when {
            pm?.isInteractive != true -> ScreenState.OFF
            km?.isKeyguardLocked == true -> ScreenState.LOCKED
            else -> ScreenState.ON
        }
        context.store.set(StateKeyToken, state)
    }
}

/**
 * 内置能力：电池与省电模式（懒加载）。
 * 通过 ACTION_BATTERY_CHANGED 与 POWER_SAVE_MODE_CHANGED 广播驱动刷新。
 */
public class BatteryCapability(
    private val context: IGlobalContext,
    private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.battery"

    public object LevelKey : StateKey<Int>("xglobal.battery.level", -1)
    public object ChargingKey : StateKey<Boolean>("xglobal.battery.charging", false)
    public object PowerSaveKey : StateKey<Boolean>("xglobal.battery.power_save", false)

    public val level: Int get() = context.store.get(LevelKey)
    public val isCharging: Boolean get() = context.store.get(ChargingKey)
    public val isPowerSave: Boolean get() = context.store.get(PowerSaveKey)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (lvl >= 0 && scale > 0) {
                        context.store.set(LevelKey, lvl * 100 / scale)
                    }
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    context.store.set(
                        ChargingKey,
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    )
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> refreshPowerSave()
            }
        }
    }

    override fun onAttach(context: IGlobalContext) {
        refreshPowerSave()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    override fun onDetach() {
        appContext.unregisterReceiver(receiver)
    }

    private fun refreshPowerSave() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        context.store.set(PowerSaveKey, pm?.isPowerSaveMode ?: false)
    }
}

/**
 * 内置能力：存储可用空间（懒加载）。
 * 定时刷新（后台不广播），每 30 秒更新一次。
 */
public class StorageCapability(
    private val context: IGlobalContext,
    private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.storage"

    public object AvailableBytesKey : StateKey<Long>("xglobal.storage.available", -1L)

    public val availableBytes: Long get() = context.store.get(AvailableBytesKey)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onAttach(context: IGlobalContext) {
        refresh()
        scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    override fun onDetach() {
        scope.cancel()
    }

    private fun refresh() {
        val dir = Environment.getDataDirectory()
        val stat = StatFs(dir.path)
        context.store.set(AvailableBytesKey, stat.availableBytes)
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
    }
}

/**
 * 内置能力：时区（懒加载，只读）。
 * 进程启动读取一次；配置变化（时区切换）时刷新。
 */
public class TimeZoneCapability(
    private val context: IGlobalContext,
    @Suppress("unused") private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.timezone"

    public object IdKey : StateKey<String>("xglobal.timezone.id", TimeZone.getDefault().id)

    public val current: String get() = context.store.get(IdKey)
    public val flow: StateFlow<String> get() = context.store.flow(IdKey)

    override fun onAttach(context: IGlobalContext) {
        refresh()
    }

    override fun onConfigurationChanged() {
        refresh()
    }

    private fun refresh() {
        context.store.set(IdKey, TimeZone.getDefault().id)
    }
}

/**
 * 内置能力：系统字体缩放（懒加载，只读）。
 * 进程启动读取一次；配置变化时刷新，辅助无障碍适配。
 */
public class FontScaleCapability(
    private val context: IGlobalContext,
    private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.fontscale"

    public object ScaleKey : StateKey<Float>("xglobal.fontscale.scale", 1.0f)

    public val current: Float get() = context.store.get(ScaleKey)
    public val flow: StateFlow<Float> get() = context.store.flow(ScaleKey)

    override fun onAttach(context: IGlobalContext) {
        refresh()
    }

    override fun onConfigurationChanged() {
        refresh()
    }

    private fun refresh() {
        context.store.set(ScaleKey, appContext.resources.configuration.fontScale)
    }
}
