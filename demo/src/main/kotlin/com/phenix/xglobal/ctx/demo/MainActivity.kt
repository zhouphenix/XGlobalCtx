package com.phenix.xglobal.ctx.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phenix.xglobal.ctx.android.LanguageKey
import com.phenix.xglobal.ctx.android.UiMode
import com.phenix.xglobal.ctx.android.UiModeKey
import com.phenix.xglobal.ctx.compose.collectAsState
import com.phenix.xglobal.ctx.core.GlobalContext
import com.phenix.xglobal.ctx.core.StateKey

/**
 * Compose 用法示例：collectAsState 观察 UiMode / Language。
 * 切换系统深浅色或语言后回到 App，界面自动刷新。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = AppGlobalContextAccess.require()
        setContent {
            DemoThemeFromGlobal(ctx) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val uiMode by UiModeKey.collectAsState(ctx)
                        val language by LanguageKey.collectAsState(ctx)

                        Text("UiMode: $uiMode", style = MaterialTheme.typography.headlineSmall)
                        Text("Language: ${language.displayLanguage}", style = MaterialTheme.typography.headlineSmall)

                        Button(onClick = { startActivity(Intent(this@MainActivity, ViewDemoActivity::class.java)) }) {
                            Text("Open View demo")
                        }
                    }
                }
            }
        }
    }
}

/** 用全局 UiMode 驱动 Material 主题（暗色时强制 darkScheme，示意）。 */
@Composable
private fun DemoThemeFromGlobal(ctx: GlobalContext, content: @Composable () -> Unit) {
    val uiMode by UiModeKey.collectAsState(ctx)
    androidx.compose.material3.MaterialTheme(content = content)
    if (uiMode == UiMode.UNSPECIFIED) return // 仅为展示读取 API；真实主题接线由 MaterialTheme 处理
}

/** demo 内访问入口（真实 SDK 场景即 AppGlobalContext.require()）。 */
private object AppGlobalContextAccess {
    fun require(): GlobalContext = com.phenix.xglobal.ctx.android.AppGlobalContext.require()
}

/** 三方自定义状态键示例：挂载自己的全局状态。 */
object DemoCounterKey : StateKey<Int>("demo.counter", 0)
