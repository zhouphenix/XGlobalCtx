package com.phenix.xglobal.ctx.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phenix.xglobal.ctx.android.AppGlobalContext
import com.phenix.xglobal.ctx.android.ScaleCapability
import com.phenix.xglobal.ctx.core.IGlobalContext

/**
 * Compose 扩展：响应式尺寸（文本 scale 与 DP scale 双轨的 Compose 入口）。
 *
 * 用 [ScaleCapability] 的配置包装 [LocalDensity]:
 * - dp = 设备基准 density × dimScale
 * - sp = 设备基准 fontScale × textScale(保留系统字体缩放)
 * scale 变化时即时重组,无需重建 Activity。
 *
 * 与 View 轨(Activity 覆写 metrics)二选一使用,不要叠加。
 * ScaleCapability 未注册时按原样提供当前 LocalDensity(无副作用,不激活能力)。
 */
@Composable
public fun ResponsiveDensity(
    context: IGlobalContext = AppGlobalContext.require(),
    content: @Composable () -> Unit,
) {
    val textScale = context.store
        .flow(ScaleCapability.TextScaleKey)
        .collectAsStateWithLifecycle()
        .value
    val dimScale = context.store
        .flow(ScaleCapability.DimScaleKey)
        .collectAsStateWithLifecycle()
        .value

    val capability = if (context.isRegistered("xglobal.scale")) {
        context.getCapability<ScaleCapability>("xglobal.scale")
    } else {
        null
    }
    val current = LocalDensity.current
    val density: Float
    val fontScale: Float
    if (capability != null && capability.baseDensity > 0f) {
        // Compose 的 sp→px = sp × fontScale × density,dimScale 已乘进 density,
        // fontScale 需除以 dimScale 补偿,保证文本 px 恒为 基准×textScale,与尺寸缩放解耦
        density = capability.baseDensity * dimScale
        fontScale = (capability.baseScaledDensity / capability.baseDensity) *
            (textScale / dimScale.coerceAtLeast(0.01f))
    } else {
        // 能力未注册/未捕获基准:以当前值为基础缩放(等效于默认配置)
        density = current.density * dimScale
        fontScale = current.fontScale * (textScale / dimScale.coerceAtLeast(0.01f))
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density = density, fontScale = fontScale),
        content = content,
    )
}
