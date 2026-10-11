package com.phenix.xglobal.ctx.android

import android.app.Activity
import android.content.Context
import android.util.DisplayMetrics
import com.phenix.xglobal.ctx.core.IGlobalCapability
import com.phenix.xglobal.ctx.core.IGlobalContext
import com.phenix.xglobal.ctx.core.StateKey
import kotlinx.coroutines.flow.StateFlow

/**
 * 内置能力:响应式尺寸(文本 scale 与 DP scale 独立双轨)。
 *
 * - View 轨:[attachHost] 后自动覆写宿主(及应用) Resources 的
 *   density / scaledDensity / densityDpi,使 dp 与 sp 按配置缩放,View 与 Compose 全局生效
 * - Compose 轨:compose 模块的 ResponsiveDensity 用 [baseDensity] / [baseScaledDensity]
 *   包装 LocalDensity,即时重组
 * - 基准捕获:首次 attachHost 记录设备原始 metrics,之后所有缩放均基于该基准,
 *   防止多次覆写叠加;detachHost 恢复原始值
 * - 未设置宿主时 Compose 轨仍可用(View 轨为 no-op);两轨不要同时使用
 */
public class ScaleCapability(
    private val context: IGlobalContext,
    @Suppress("unused") private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.scale"

    public object TextScaleKey : StateKey<Float>("xglobal.scale.text", 1.0f)
    public object DimScaleKey : StateKey<Float>("xglobal.scale.dim", 1.0f)

    public val textScale: Float get() = context.store.get(TextScaleKey)
    public val dimScale: Float get() = context.store.get(DimScaleKey)
    public val textScaleFlow: StateFlow<Float> get() = context.store.flow(TextScaleKey)
    public val dimScaleFlow: StateFlow<Float> get() = context.store.flow(DimScaleKey)

    /** 宿主 Activity,由使用方 attachHost 注册、detachHost 注销。 */
    public var host: Activity? = null
        private set

    // 设备原始基准(首次 attach 捕获,缩放永远基于它,防叠加)
    private var deviceDensity = 0f
    private var deviceScaledDensity = 0f
    private var deviceDensityDpi = 0

    /** 设备原始 density(供 Compose 轨构建基准);未捕获时为 0。 */
    public val baseDensity: Float get() = deviceDensity

    /** 设备原始 scaledDensity(供 Compose 轨换算 sp);未捕获时为 0。 */
    public val baseScaledDensity: Float get() = deviceScaledDensity

    /** 注册宿主并立即应用当前 scale 配置(View 轨)。 */
    public fun attachHost(activity: Activity) {
        host = activity
        captureBase()
        applyTo(activity)
    }

    override fun onAttach(context: IGlobalContext) {
        // 注册即捕获基准:保证未走 View 轨(纯 Compose 用法)时 pxToDp 等助手也有基准可依
        captureBase()
    }

    /** 捕获设备原始 metrics(仅一次,后续缩放均基于它,防叠加)。 */
    private fun captureBase() {
        if (deviceDensity > 0f) return
        val m = appContext.resources.displayMetrics
        deviceDensity = m.density
        deviceScaledDensity = m.scaledDensity
        deviceDensityDpi = m.densityDpi
    }

    /** 注销宿主并恢复设备原始 metrics。 */
    public fun detachHost(activity: Activity) {
        if (host !== activity) return
        runCatching {
            if (deviceDensity > 0f) {
                listOf(
                    activity.resources.displayMetrics,
                    activity.application.resources.displayMetrics,
                ).forEach { m ->
                    m.density = deviceDensity
                    @Suppress("DEPRECATION")
                    m.scaledDensity = deviceScaledDensity
                    m.densityDpi = deviceDensityDpi
                }
            }
        }
        host = null
    }

    /** 设置文本缩放(1.0 = 设备默认,限 0.5..3.0),立即应用到宿主(View 轨)。 */
    public fun setTextScale(scale: Float) {
        context.store.set(TextScaleKey, scale.coerceIn(0.5f, 3f))
        host?.let { applyTo(it) }
    }

    /** 设置 DP 尺寸缩放(1.0 = 设备默认,限 0.5..3.0),立即应用到宿主(View 轨)。 */
    public fun setDimScale(scale: Float) {
        context.store.set(DimScaleKey, scale.coerceIn(0.5f, 3f))
        host?.let { applyTo(it) }
    }

    /** 将当前 scale 配置覆写到宿主及应用级 Resources(View 轨)。 */
    public fun applyTo(activity: Activity) {
        runCatching {
            overrideMetrics(activity.resources.displayMetrics)
            overrideMetrics(activity.application.resources.displayMetrics)
        }
    }

    /**
     * 遗留 px 值 → 等效 dp:结果随 [DimScaleKey] 统一缩放。
     * 用于把硬编码 px 的旧控件接入响应式体系(px = dp × 基准密度)。
     */
    public fun pixelsToDp(pixels: Float): Float {
        if (deviceDensity <= 0f) return pixels
        return pixels / deviceDensity
    }

    /** [pixelsToDp] 的 Int 重载。 */
    public fun pixelsToDp(pixels: Int): Float = pixelsToDp(pixels.toFloat())

    /**
     * 遗留 px 值 → 当前缩放下的等效 px:供只能接收 px 的 API(自绘/Canvas 等)使用,
     * 结果随 [DimScaleKey] 缩放。
     */
    public fun scaledPixels(pixels: Float): Float =
        if (deviceDensity <= 0f) pixels else pixels * dimScale

    private fun overrideMetrics(metrics: DisplayMetrics) {
        if (deviceDensity <= 0f) return
        metrics.density = deviceDensity * dimScale
        @Suppress("DEPRECATION")
        metrics.scaledDensity = deviceScaledDensity * textScale
        metrics.densityDpi = (deviceDensityDpi * dimScale).toInt().coerceAtLeast(1)
    }
}
