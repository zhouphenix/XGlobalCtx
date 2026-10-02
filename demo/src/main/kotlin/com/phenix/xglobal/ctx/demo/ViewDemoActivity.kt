package com.phenix.xglobal.ctx.demo

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.phenix.xglobal.ctx.android.AppGlobalContext
import com.phenix.xglobal.ctx.android.LanguageKey
import com.phenix.xglobal.ctx.android.UiModeKey
import com.phenix.xglobal.ctx.view.observe

/**
 * View 体系用法示例：StateKey.observe 以生命周期安全方式观察全局状态。
 */
class ViewDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = AppGlobalContext.require()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val uiModeText = TextView(this).apply { textSize = 20f }
        val langText = TextView(this).apply { textSize = 20f }
        root.addView(uiModeText)
        root.addView(langText)
        setContentView(root)

        // 观察全局状态：内部 repeatOnLifecycle(STARTED)，随生命周期自动取消
        UiModeKey.observe(this) { uiMode ->
            uiModeText.text = "UiMode: $uiMode"
        }
        LanguageKey.observe(this) { locale ->
            langText.text = "Language: ${locale.displayLanguage}"
        }
    }
}
