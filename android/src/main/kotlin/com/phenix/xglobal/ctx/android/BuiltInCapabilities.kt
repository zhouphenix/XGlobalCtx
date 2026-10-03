package com.phenix.xglobal.ctx.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.phenix.xglobal.ctx.core.IGlobalCapability
import com.phenix.xglobal.ctx.core.IGlobalContext
import com.phenix.xglobal.ctx.core.StateKey
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** UiMode 取值：跟随系统，不持久化、不支持覆盖。 */
public enum class UiMode { DARK, LIGHT, UNSPECIFIED }

/** 状态键：当前 UiMode。 */
public object UiModeKey : StateKey<UiMode>("xglobal.uimode", UiMode.UNSPECIFIED)

/** 状态键：系统语言 Locale。 */
public object LanguageKey : StateKey<Locale>("xglobal.language", Locale.getDefault())

/**
 * 内置能力：UiMode（深浅色），跟随系统。
 * 进程启动读取一次；配置变化由 AppLifecycleCallbacks → 内核 → [onConfigurationChanged] 驱动刷新。
 */
public class UiModeCapability(
    private val context: IGlobalContext,
    private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.uimode"

    /** 当前 UiMode，同步读取，任意位置可调。 */
    public val current: UiMode get() = context.store.get(UiModeKey)

    /** UiMode 响应式流。 */
    public val flow: StateFlow<UiMode> get() = context.store.flow(UiModeKey)

    /** 懒加载：首次 getCapability 访问时才读取初始值。 */
    override fun onAttach(context: IGlobalContext) {
        context.store.set(UiModeKey, readFromConfig())
    }

    override fun onConfigurationChanged() {
        context.store.set(UiModeKey, readFromConfig())
    }

    private fun readFromConfig(): UiMode {
        val uiMode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (uiMode) {
            Configuration.UI_MODE_NIGHT_YES -> UiMode.DARK
            Configuration.UI_MODE_NIGHT_NO -> UiMode.LIGHT
            else -> UiMode.UNSPECIFIED
        }
    }
}

/**
 * 内置能力：系统语言（只读）。
 * 进程启动读取一次，配置变化时跟随系统刷新；不做语言覆盖。
 */
public class LanguageCapability(
    private val context: IGlobalContext,
    @Suppress("unused") private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.language"

    /** 当前系统语言 Locale，同步读取。 */
    public val current: Locale get() = context.store.get(LanguageKey)

    /** 语言响应式流。 */
    public val flow: StateFlow<Locale> get() = context.store.flow(LanguageKey)

    /** 懒加载：首次 getCapability 访问时才读取初始值。 */
    override fun onAttach(context: IGlobalContext) {
        context.store.set(LanguageKey, readLocale())
    }

    override fun onConfigurationChanged() {
        context.store.set(LanguageKey, readLocale())
    }

    private fun readLocale(): Locale {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.resources.configuration.locales[0]?.let { return it }
        }
        @Suppress("DEPRECATION")
        return appContext.resources.configuration.locale ?: Locale.getDefault()
    }
}

/**
 * 内置能力：前后台状态。
 * 实际判定由 AppLifecycleCallbacks 驱动内核 notifyForeground/notifyBackground，
 * 本能力把内核状态以能力形式暴露。
 */
public class ForegroundCapability(
    private val context: IGlobalContext,
) : IGlobalCapability {

    override val id: String = "xglobal.foreground"

    /** 当前是否前台。 */
    public val isForeground: Boolean get() = context.isForeground

    /** 前后台响应式流。 */
    public val foregroundFlow: StateFlow<Boolean> get() = context.foregroundFlow
}
