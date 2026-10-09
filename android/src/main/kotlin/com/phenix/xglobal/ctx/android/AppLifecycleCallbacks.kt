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

    fun start() {
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                AppGlobalContext.onConfigurationChanged()
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
        val prev = activityCount.value
        activityCount.value = (prev - 1).coerceAtLeast(0)
        if (prev == 1 && activityCount.value == 0) ctx.notifyBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) {
        // 统一上报分屏/多窗口状态（进出分屏时 Activity 必然重新 resume）
        // 注:ActivityLifecycleCallbacks 无 onActivityMultiWindowModeChanged,故在 resume 时读取
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppGlobalContext.multiWindowFlow.value = activity.isInMultiWindowMode
        }
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
