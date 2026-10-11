package com.phenix.xglobal.ctx.android

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 系统回调接线：
 * - Activity 级计数实现前后台判定（无 Activity 即后台）
 * - onConfigurationChanged 转发给内核（驱动 UiMode / Language 刷新）
 *
 * 通过 ActivityLifecycleCallbacks 而非 ProcessLifecycleOwner，
 * 避免 SDK 引入 lifecycle-process 依赖。
 */
internal class AppLifecycleCallbacks(
    private val app: Application,
    private val ctx: com.phenix.xglobal.ctx.core.GlobalContextImpl,
) : Application.ActivityLifecycleCallbacks {

    private val activityCount = MutableStateFlow(0)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 当前前台(已 resume)Activity,弱引用持有。
     * 归属本回调类实例(生命周期与 Application 一致)而非全局单例:
     * 单例对象不长期挂着 Activity 字段,无静态引用泄漏告警。
     * 其 resources 配置总是最新(configChanges 或重建后均如此),
     * UiMode/Language 等读取方用它兜底,规避 appContext 配置滞后。
     */
    private var resumedRef: java.lang.ref.WeakReference<Activity>? = null

    /** 前台 Activity 的最新配置;无前台 Activity(或已被回收)时为 null。 */
    internal fun resumedActivityConfiguration(): Configuration? =
        resumedRef?.get()?.resources?.configuration

    /**
     * 定时复核链:Application 级配置回调可能先于前台 Activity 的配置就地更新执行
     * (读到的 NIGHT_MASK 为旧值,且 configChanges 场景无后续回调纠正)。
     * 调度时把 Activity 捕获到局部闭包——强引用最多存活 1s 且非静态字段;
     * 回调后延时 100/300/1000ms 各复核一次,读最新配置覆盖写状态键。
     * 1s 尾点兜底慢设备;StateKey 写入经 StateFlow 去重,重复写同值不引起观察方抖动。
     */
    private fun scheduleConfigRecheck() {
        val resumed = resumedRef?.get() ?: return
        listOf(100L, 300L, 1000L).forEach { delayMs ->
            mainHandler.postDelayed({
                val activity = resumedRef?.get() ?: return@postDelayed
                if (activity !== resumed) return@postDelayed
                val config = activity.resources.configuration
                if (ctx.isRegistered("xglobal.uimode")) {
                    when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                        Configuration.UI_MODE_NIGHT_YES -> ctx.store.set(UiModeKey, UiMode.DARK)
                        Configuration.UI_MODE_NIGHT_NO -> ctx.store.set(UiModeKey, UiMode.LIGHT)
                        else -> Unit
                    }
                }
                if (ctx.isRegistered("xglobal.language")) {
                    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        config.locales.get(0)
                    } else {
                        @Suppress("DEPRECATION")
                        config.locale
                    }
                    locale?.let { ctx.store.set(LanguageKey, it) }
                }
            }, delayMs)
        }
    }

    fun start() {
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                AppGlobalContext.onConfigurationChanged()
                scheduleConfigRecheck()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    override fun onActivityStarted(activity: Activity) {
        val prev = activityCount.value
        activityCount.value = prev + 1
        if (prev == 0) ctx.notifyForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        if (resumedRef?.get() === activity) {
            resumedRef = null
        }
        val prev = activityCount.value
        activityCount.value = (prev - 1).coerceAtLeast(0)
        if (prev == 1 && activityCount.value == 0) ctx.notifyBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) {
        resumedRef = java.lang.ref.WeakReference(activity)
        // 分发宿主恢复事件:显隐意图等需要随宿主可见性重应用的能力自行处理(库内闭环)
        AppGlobalContext.onHostResumed(activity)
        // 统一上报分屏/多窗口状态（进出分屏时 Activity 必然重新 resume）
        // 注:ActivityLifecycleCallbacks 无 onActivityMultiWindowModeChanged,故在 resume 时读取
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppGlobalContext.multiWindowFlow.value = activity.isInMultiWindowMode
        }
        // 配置兜底刷新:Application 级回调与 appContext.resources 在部分时序下
        // (setDefaultNightMode 后 Activity 重建等)拿到的是旧配置,且此后无回调纠正。
        // resume 时直接读 Activity 自身的最新配置刷新状态键(仅能力已注册时写,避免覆盖默认值)
        ctx.notifyConfigurationChanged()
        val config = activity.resources.configuration
        if (ctx.isRegistered("xglobal.uimode")) {
            when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES ->
                    ctx.store.set(UiModeKey, UiMode.DARK)
                Configuration.UI_MODE_NIGHT_NO ->
                    ctx.store.set(UiModeKey, UiMode.LIGHT)
                else -> Unit
            }
        }
        if (ctx.isRegistered("xglobal.language")) {
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                config.locale
            }
            locale?.let { ctx.store.set(LanguageKey, it) }
        }
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
