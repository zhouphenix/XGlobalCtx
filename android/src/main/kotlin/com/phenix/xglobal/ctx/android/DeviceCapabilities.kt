package com.phenix.xglobal.ctx.android

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
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
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
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

/**
 * 内置能力：分屏模式监测。
 * - 状态来源：库内置 ActivityLifecycleCallbacks 统一上报（onActivityResumed /
 *   onActivityMultiWindowModeChanged），能力激活时桥接到 Store
 * - 勾选启用后实时反映分屏进出；也可由宿主手动调 [onMultiWindowModeChanged] 转发
 */
public class SplitScreenCapability(
    private val context: IGlobalContext,
    @Suppress("unused") private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.splitscreen"

    public object InMultiWindowKey : StateKey<Boolean>("xglobal.splitscreen.active", false)

    public val isInMultiWindow: Boolean get() = context.store.get(InMultiWindowKey)
    public val flow: StateFlow<Boolean> get() = context.store.flow(InMultiWindowKey)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onAttach(context: IGlobalContext) {
        // 立即对齐一次当前状态,再持续订阅统一上报通道
        context.store.set(InMultiWindowKey, AppGlobalContext.multiWindowFlow.value)
        scope.launch {
            AppGlobalContext.multiWindowFlow.collect { inMulti ->
                context.store.set(InMultiWindowKey, inMulti)
            }
        }
    }

    override fun onDetach() {
        scope.cancel()
        context.store.set(InMultiWindowKey, false)
    }

    /** 宿主也可手动转发 Activity.onMultiWindowModeChanged（一般无需调用,库已统一上报）。 */
    public fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        context.store.set(InMultiWindowKey, isInMultiWindowMode)
    }
}

/**
 * 内置能力：系统栏（statusbar / bottombar）显隐控制与监听，含外观定制。
 *
 * - 显隐：[hideSystemBars] / [showSystemBars]
 * - 外观：[setIconDark]（图标深浅）、[setStatusBarColor] / [setNavigationBarColor] /
 *   [setSystemBarsColor]（背景色）、[setSystemBarsTransparent]（全透明）、
 *   [setSystemBarsBackground]（颜色+透明度）、[setFitsSystemWindows]（避让）
 * - 监听：attachHost 自动接线 WindowInsets,visibleFlow 实时反映系统栏显隐
 * - 全部控制作用于 [attachHost] 注册的宿主 Activity;未设置宿主时为 no-op
 */
public class SystemBarsCapability(
    private val context: IGlobalContext,
    @Suppress("unused") private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.systembars"

    public object VisibleKey : StateKey<Boolean>("xglobal.systembars.visible", true)
    public object StatusBarVisibleKey : StateKey<Boolean>("xglobal.systembars.status_visible", true)
    public object NavBarVisibleKey : StateKey<Boolean>("xglobal.systembars.nav_visible", true)
    public object StatusFitsKey : StateKey<Boolean>("xglobal.systembars.status_fits", false)
    public object NavFitsKey : StateKey<Boolean>("xglobal.systembars.nav_fits", false)
    public object IconDarkKey : StateKey<Boolean>("xglobal.systembars.icons_dark", false)
    public object StatusBarColorKey : StateKey<Int>("xglobal.systembars.status_color", Color.BLACK)
    public object NavBarColorKey : StateKey<Int>("xglobal.systembars.nav_color", Color.BLACK)

    public val isVisible: Boolean get() = context.store.get(VisibleKey)
    public val isStatusBarVisible: Boolean get() = context.store.get(StatusBarVisibleKey)
    public val isNavBarVisible: Boolean get() = context.store.get(NavBarVisibleKey)
    public val isStatusFits: Boolean get() = context.store.get(StatusFitsKey)
    public val isNavFits: Boolean get() = context.store.get(NavFitsKey)
    public val isIconDark: Boolean get() = context.store.get(IconDarkKey)
    public val statusBarColor: Int get() = context.store.get(StatusBarColorKey)
    public val navigationBarColor: Int get() = context.store.get(NavBarColorKey)
    public val visibleFlow: StateFlow<Boolean> get() = context.store.flow(VisibleKey)
    public val statusFitsFlow: StateFlow<Boolean> get() = context.store.flow(StatusFitsKey)
    public val navFitsFlow: StateFlow<Boolean> get() = context.store.flow(NavFitsKey)

    /** 宿主 Activity,由使用方在 Activity.onCreate 中注册、onDestroy 中置空。 */
    public var host: Activity? = null
        private set

    /** 最近一次 WindowInsets,用于 fits 状态切换时重算避让 padding。 */
    private var lastInsets: WindowInsets? = null
    private var contentView: ViewGroup? = null

    /**
     * API 35+ 系统强制 edge-to-edge,window.statusBarColor/navigationBarColor 被忽略,
     * 改用挂在 decorView 上的 scrim View 绘制栏底色(高度随 insets 变化)。
     */
    private var statusScrim: View? = null
    private var navScrim: View? = null

    /**
     * insets 回写保护窗:API 显隐写入后短暂时窗内,insets 回调不再回写可见性键,
     * 避免 systemUiVisibility 变更后异步重派发的滞后 insets 把状态拉回旧值(勾选不同步)。
     */
    @Volatile
    private var suppressInsetsSyncUntil = 0L

    private fun armInsetsGuard() {
        suppressInsetsSyncUntil = SystemClock.uptimeMillis() + 500
    }

    private fun insetsGuardActive(): Boolean =
        SystemClock.uptimeMillis() < suppressInsetsSyncUntil

    private val insetsListener = android.view.View.OnApplyWindowInsetsListener { _, insets ->
        onInsetsChanged(insets)
        insets
    }

    /** 注册宿主 Activity（用于执行系统栏控制,并自动接线 WindowInsets 显隐监听）。 */
    public fun attachHost(activity: Activity) {
        host = activity
        // 监听挂在内容视图(android.R.id.content)而非 decorView:
        // decorView 上设置会覆盖系统内部的 insets 分发,造成布局/黑屏异常
        runCatching {
            contentView = activity.window.decorView.findViewById(android.R.id.content)
            contentView?.setOnApplyWindowInsetsListener(insetsListener)
        }
        ensureScrims(activity)
        applyFitsPadding()
    }

    /** 注销宿主（同时移除 insets 监听与 scrim 并复位避让 padding）。 */
    public fun detachHost(activity: Activity) {
        if (host === activity) {
            runCatching {
                contentView?.setPadding(0, 0, 0, 0)
                contentView?.setOnApplyWindowInsetsListener(null)
            }
            runCatching {
                statusScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
                navScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
            statusScrim = null
            navScrim = null
            contentView = null
            lastInsets = null
            host = null
        }
    }

    /**
     * API 35+ 装载 scrim View 作为实体栏底色(window.statusBarColor 已被系统忽略)。
     * 不拦截触摸(非 clickable,触摸事件穿透到下层内容)。
     */
    private fun ensureScrims(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        runCatching {
            if (statusScrim?.parent !== decor) {
                statusScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
                val s = View(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setBackgroundColor(
                        this@SystemBarsCapability.context.store.get(StatusBarColorKey)
                    )
                }
                decor.addView(
                    s,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP
                    ),
                )
                statusScrim = s
            }
            if (navScrim?.parent !== decor) {
                navScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
                val n = View(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setBackgroundColor(
                        this@SystemBarsCapability.context.store.get(NavBarColorKey)
                    )
                }
                decor.addView(
                    n,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM
                    ),
                )
                navScrim = n
            }
            updateScrimHeights()
        }
    }

    /** insets 提供的栏高缓存(与显隐状态无关,供 scrim 高度计算)。 */
    private var statusBarInsetHeight = 0
    private var navBarInsetHeight = 0

    /**
     * scrim 高度 = 显隐状态 × 栏高缓存:
     * 高度跟随显隐 API 即时更新,不依赖异步的 insets 重派发,
     * 保证隐藏时彩条立即消失、显示时背景立即出现。
     */
    private fun updateScrimHeights() {
        val statusH = if (context.store.get(StatusBarVisibleKey)) statusBarInsetHeight else 0
        val navH = if (context.store.get(NavBarVisibleKey)) navBarInsetHeight else 0
        statusScrim?.let { s ->
            s.layoutParams = (s.layoutParams as? FrameLayout.LayoutParams)?.apply {
                height = statusH
            }
            s.requestLayout()
        }
        navScrim?.let { n ->
            n.layoutParams = (n.layoutParams as? FrameLayout.LayoutParams)?.apply {
                height = navH
            }
            n.requestLayout()
        }
    }

    /** 隐藏状态栏+导航栏。 */
    public fun hideSystemBars() {
        hideStatusBar()
        hideNavBar()
    }

    /** 显示状态栏+导航栏（普通布局,底色可见可配色）。 */
    public fun showSystemBars() {
        showStatusBar()
        showNavBar()
    }

    /** 仅隐藏状态栏（内容延伸到状态栏区域）。 */
    public fun hideStatusBar() {
        val activity = host ?: return
        armInsetsGuard()
        context.store.set(StatusBarVisibleKey, false)
        @Suppress("DEPRECATION")
        val decor = activity.window.decorView
        decor.systemUiVisibility = decor.systemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.window.insetsController?.hide(WindowInsets.Type.statusBars()) }
        }
        syncVisibleKey()
        updateScrimHeights()
    }

    /** 仅显示状态栏（实体条,底色可见）。 */
    public fun showStatusBar() {
        val activity = host ?: return
        armInsetsGuard()
        context.store.set(StatusBarVisibleKey, true)
        @Suppress("DEPRECATION")
        val decor = activity.window.decorView
        decor.systemUiVisibility = decor.systemUiVisibility and
            View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.window.insetsController?.show(WindowInsets.Type.statusBars()) }
        }
        syncVisibleKey()
        updateScrimHeights()
    }

    /** 仅隐藏导航栏（内容延伸到底栏区域）。 */
    public fun hideNavBar() {
        val activity = host ?: return
        armInsetsGuard()
        context.store.set(NavBarVisibleKey, false)
        @Suppress("DEPRECATION")
        val decor = activity.window.decorView
        decor.systemUiVisibility = decor.systemUiVisibility or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.window.insetsController?.hide(WindowInsets.Type.navigationBars()) }
        }
        syncVisibleKey()
        updateScrimHeights()
    }

    /** 仅显示导航栏（实体条,底色可见）。 */
    public fun showNavBar() {
        val activity = host ?: return
        armInsetsGuard()
        context.store.set(NavBarVisibleKey, true)
        @Suppress("DEPRECATION")
        val decor = activity.window.decorView
        decor.systemUiVisibility = decor.systemUiVisibility and
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv() and
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION.inv() or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.window.insetsController?.show(WindowInsets.Type.navigationBars()) }
        }
        syncVisibleKey()
        updateScrimHeights()
    }

    /** 汇总两栏可见性到 [VisibleKey]。 */
    private fun syncVisibleKey() {
        context.store.set(
            VisibleKey,
            context.store.get(StatusBarVisibleKey) || context.store.get(NavBarVisibleKey),
        )
    }

    /**
     * 切换系统栏避让（状态栏 + 导航栏同时,兼容旧用法）。
     * 自管理 padding 实现:开启时按最近 insets 给 content 视图加避让 padding,
     * 关闭时清零;对 Compose 内容同样生效(不经 View 层 fitsSystemWindows——其对该内容无效)。
     */
    public fun setFitsSystemWindows(enabled: Boolean) {
        setStatusFits(enabled)
        setNavFits(enabled)
    }

    /** 仅切换状态栏避让(顶部 padding 按 top inset)。 */
    public fun setStatusFits(enabled: Boolean) {
        context.store.set(StatusFitsKey, enabled)
        applyFitsPadding()
    }

    /** 仅切换导航栏(底部栏)避让(底部 padding 按 bottom inset)。 */
    public fun setNavFits(enabled: Boolean) {
        context.store.set(NavFitsKey, enabled)
        applyFitsPadding()
    }

    /**
     * 按分栏 fits 状态与最近 insets 应用避让 padding(自管理,分栏覆盖):
     * - 状态栏避让开 → top += top inset
     * - 导航栏避让开 → bottom += bottom inset
     * 对 Compose 内容同样生效——不经 View 层 fitsSystemWindows(其对该内容无效)。
     */
    private fun applyFitsPadding() {
        val content = contentView ?: return
        val insets = lastInsets
        val top = if (context.store.get(StatusFitsKey)) insets?.systemWindowInsetTop ?: 0 else 0
        val bottom = if (context.store.get(NavFitsKey)) insets?.systemWindowInsetBottom ?: 0 else 0
        content.setPadding(
            insets?.systemWindowInsetLeft ?: 0,
            top,
            insets?.systemWindowInsetRight ?: 0,
            bottom,
        )
    }

    /**
     * 设置系统栏图标深浅：dark=true 图标变深色（浅色背景用），dark=false 图标白色（深色背景用）。
     * 内部走 SYSTEM_UI_FLAG_LIGHT_STATUS_BAR / LIGHT_NAVIGATION_BAR。
     */
    public fun setIconDark(dark: Boolean) {
        val activity = host ?: return
        context.store.set(IconDarkKey, dark)
        @Suppress("DEPRECATION")
        val decor = activity.window.decorView
        val flags = decor.systemUiVisibility
        val newFlags = if (dark) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else -1)
        }
        decor.systemUiVisibility = newFlags
    }

    /** 设置状态栏背景色（ARGB 整型,如 0xFF3F51B5）。同时更新 scrim(API 35+ 途径)。 */
    public fun setStatusBarColor(color: Int) {
        val activity = host ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { activity.window.statusBarColor = color }
        }
        context.store.set(StatusBarColorKey, color)
        statusScrim?.setBackgroundColor(color)
    }

    /** 设置导航栏背景色（ARGB 整型）。同时更新 scrim(API 35+ 途径)。 */
    public fun setNavigationBarColor(color: Int) {
        val activity = host ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { activity.window.navigationBarColor = color }
        }
        context.store.set(NavBarColorKey, color)
        navScrim?.setBackgroundColor(color)
    }

    /** 同时设置状态栏与导航栏背景色。 */
    public fun setSystemBarsColor(color: Int) {
        setStatusBarColor(color)
        setNavigationBarColor(color)
    }

    /** 状态栏独立背景:颜色 + 不透明度(0..1)。 */
    public fun setStatusBarBackground(color: Int, alpha: Float = 1f) {
        val a = alpha.coerceIn(0f, 1f)
        setStatusBarColor((color and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
    }

    /** 导航栏独立背景:颜色 + 不透明度(0..1)。 */
    public fun setNavBarBackground(color: Int, alpha: Float = 1f) {
        val a = alpha.coerceIn(0f, 1f)
        setNavigationBarColor((color and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
    }

    /** 系统栏全透明（内容延伸到栏下时配合 LAYOUT_* 标志实现沉浸）。 */
    public fun setSystemBarsTransparent() {
        setStatusBarColor(Color.TRANSPARENT)
        setNavigationBarColor(Color.TRANSPARENT)
    }

    /**
     * 自定义系统栏背景：颜色 + 不透明度（0..1）。
     * 透明度 <1 时自动叠加到颜色 alpha 上,便于做半透明栏。
     */
    public fun setSystemBarsBackground(color: Int, alpha: Float = 1f) {
        val a = alpha.coerceIn(0f, 1f)
        val withAlpha = (color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        setStatusBarColor(withAlpha)
        setNavigationBarColor(withAlpha)
    }

    /**
     * 自定义窗口（内容区）背景色——即"自定义背景"。
     * 作用于 decorView 背景,系统栏透明时可透出该颜色形成整体配色。
     */
    public fun setWindowBackground(color: Int) {
        val activity = host ?: return
        runCatching {
            activity.window.decorView.setBackgroundColor(color)
        }
    }

    /** 宿主转发 Activity.onInsetsChanged / setOnApplyWindowInsetsListener 回调,驱动显隐监听。 */
    public fun onInsetsChanged(insets: WindowInsets) {
        lastInsets = insets
        // 栏高缓存始终更新(供 scrim 高度计算),不受保护窗影响
        statusBarInsetHeight = insets.systemWindowInsetTop
        navBarInsetHeight = insets.systemWindowInsetBottom
        // 保护窗内跳过可见性回写:刚由 API 写入的显隐状态不被滞后 insets 覆盖
        if (!insetsGuardActive()) {
            // per-bar 可见性:top inset 对应状态栏,bottom inset 对应导航栏
            context.store.set(StatusBarVisibleKey, insets.systemWindowInsetTop > 0)
            context.store.set(NavBarVisibleKey, insets.systemWindowInsetBottom > 0)
            context.store.set(
                VisibleKey,
                insets.systemWindowInsetTop > 0 || insets.systemWindowInsetBottom > 0,
            )
        }
        applyFitsPadding()
        updateScrimHeights()
    }
}
