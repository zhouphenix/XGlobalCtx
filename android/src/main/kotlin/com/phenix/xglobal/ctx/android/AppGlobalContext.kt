package com.phenix.xglobal.ctx.android

import android.app.Application
import android.content.Context
import com.phenix.xglobal.ctx.core.IGlobalContext
import com.phenix.xglobal.ctx.core.GlobalContextImpl
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局上下文单例门面（Android 侧唯一访问入口）。
 *
 * 初始化方式二选一：
 * - 自动：库内置 [CtxAutoInitProvider] 在进程启动时完成，三方零接入成本；
 * - 手动：三方在 Application.onCreate 中调用 [init]（幂等）。
 *
 * 未初始化时调用 [require] 会抛出 [IllegalStateException]，而不是静默 NPE。
 */
public object AppGlobalContext {

    /** 分屏/多窗口状态通道：由 AppLifecycleCallbacks 统一上报，SplitScreenCapability 桥接到 Store。 */
    internal val multiWindowFlow = MutableStateFlow(false)

    @Volatile
    private var impl: GlobalContextImpl? = null

    /**
     * 初始化（幂等）。建议传 Application context；
     * 传 Activity 等亦可，内部会取 applicationContext。
     */
    public fun init(context: Context): IGlobalContext {
        impl?.let { return it }
        val app = context.applicationContext as? Application
            ?: error("AppGlobalContext.init must be called with an Application context")
        val ctx = GlobalContextImpl()
        synchronized(this) {
            if (impl != null) return impl as IGlobalContext
            impl = ctx
        }
        attachBuiltInCapabilities(ctx, app)
        return ctx
    }

    /** 获取全局上下文；未初始化时抛 [IllegalStateException]。 */
    public fun require(): IGlobalContext =
        impl ?: throw IllegalStateException(
            "AppGlobalContext not initialized. " +
                "Ensure the library's auto-init provider is not stripped, " +
                "or call AppGlobalContext.init(context) in Application.onCreate."
        )

    /** 是否已初始化。 */
    public fun isInitialized(): Boolean = impl != null

    internal fun requireImpl(): GlobalContextImpl = require() as GlobalContextImpl

    /** 平台层转发系统配置变化（供生命周期回调使用）。 */
    internal fun onConfigurationChanged() {
        impl?.notifyConfigurationChanged()
    }

    /** 平台层转发进程退出。 */
    internal fun onTerminate() {
        impl?.terminate()
        impl = null
    }

    /**
     * 系统回调接线：Activity 计数驱动前后台、ComponentCallbacks 驱动配置变化。
     * 这是内核基建,始终启用;具体能力（UiMode/Language/Foreground 等）由使用方按需 register。
     */
    private fun attachBuiltInCapabilities(ctx: GlobalContextImpl, app: Application) {
        val lifecycle = AppLifecycleCallbacks(app, ctx)
        app.registerActivityLifecycleCallbacks(lifecycle)
        lifecycle.start()
    }
}
