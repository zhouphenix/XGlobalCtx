package com.phenix.xglobal.ctx.demo

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.phenix.xglobal.ctx.android.AppGlobalContext
import com.phenix.xglobal.ctx.android.LanguageKey
import com.phenix.xglobal.ctx.android.PaletteCapability
import com.phenix.xglobal.ctx.android.ScaleCapability
import com.phenix.xglobal.ctx.android.UiModeKey
import com.phenix.xglobal.ctx.view.observe

/**
 * View 体系用法示例：StateKey.observe 以生命周期安全方式观察全局状态。
 * 同时演示 ScaleCapability 的 View 轨：attachHost 后文本 sp 随 textScale 缩放
 * （View 轨与 demo 主界面的 Compose 轨独立，各自 Activity 二选一）。
 */
class ViewDemoActivity : AppCompatActivity() {

    private var scaleCapability: ScaleCapability? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = AppGlobalContext.require()

        // View 轨:绑定本 Activity 为宿主,metrics 覆写即时生效(基准只捕获一次,防叠加)
        if (ctx.isRegistered("xglobal.scale")) {
            scaleCapability = ctx.getCapability<ScaleCapability>("xglobal.scale")
                .also { it.attachHost(this) }
        }
        // dp→px 换算须经 displayMetrics(attachHost 后 density 含 dimScale),
        // 这样 padding 才随"尺寸缩放"变化(直接写 px 不跟随)
        fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
            ).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val uiModeText = TextView(this).apply { textSize = 20f }
        val langText = TextView(this).apply { textSize = 20f }
        val scaleText = TextView(this).apply { textSize = 20f }
        root.addView(uiModeText)
        root.addView(langText)
        root.addView(scaleText)
        setContentView(root)

        // 观察全局状态：内部 repeatOnLifecycle(STARTED)，随生命周期自动取消
        UiModeKey.observe(this) { uiMode ->
            uiModeText.text = "UiMode: $uiMode"
        }
        LanguageKey.observe(this) { locale ->
            langText.text = "Language: ${locale.displayLanguage}"
        }
        // 文本缩放实时反馈（跳过初值,避免启动时闪烁）
        ScaleCapability.TextScaleKey.observe(this) { scale ->
            scaleText.text = "文本缩放: ${"%.2f".format(scale)}x"
        }
        // 已启用 Scale 时展示当前值
        if (ctx.isRegistered("xglobal.scale")) {
            scaleText.text = "文本缩放: ${"%.2f".format(ctx.getCapability<ScaleCapability>("xglobal.scale").textScale)}x"
        } else {
            scaleText.text = "文本缩放: 未启用 Scale 能力"
        }

        // 全局换肤联动:观察全局肤色键(Palette "应用到全局 App UI" 勾选后生效),
        // 主界面换肤实时同步到本页窗口背景,文字对比度按亮度自适应
        fun applyDefaultChrome() {
            // 默认态:浅色背景 + 深色文字,与 UiMode 无关恒保对比
            //(dark 模式下主题未适配时解析 textColorPrimary 会得到深色字,配暗底不可读)
            window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#EDEDED")))
            listOf(uiModeText, langText, scaleText).forEach { it.setTextColor(Color.BLACK) }
        }
        PaletteCapability.GlobalSkinKey.observe(this) { color ->
            val applied = ctx.store.get(PaletteCapability.GlobalApplyKey) && color != 0
            val texts = listOf(uiModeText, langText, scaleText)
            if (applied) {
                window.setBackgroundDrawable(ColorDrawable(color))
                val dark = isDarkColor(color)
                texts.forEach { it.setTextColor(if (dark) Color.WHITE else Color.BLACK) }
            } else {
                applyDefaultChrome()
            }
        }
        // 初次进入也按默认态着色(全局换肤未开启时保证文字可见)
        applyDefaultChrome()
    }

    override fun onDestroy() {
        // 解绑宿主并恢复设备原始 metrics,防止泄漏与全局残留
        scaleCapability?.detachHost(this)
        super.onDestroy()
    }
}
