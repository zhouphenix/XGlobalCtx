package com.phenix.xglobal.ctx.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phenix.xglobal.ctx.android.BatteryCapability
import com.phenix.xglobal.ctx.android.FontScaleCapability
import com.phenix.xglobal.ctx.android.LanguageKey
import com.phenix.xglobal.ctx.android.NetworkCapability
import com.phenix.xglobal.ctx.android.ScreenCapability
import com.phenix.xglobal.ctx.android.StorageCapability
import com.phenix.xglobal.ctx.android.TimeZoneCapability
import com.phenix.xglobal.ctx.android.UiMode
import com.phenix.xglobal.ctx.android.UiModeKey
import com.phenix.xglobal.ctx.compose.collectAsState
import com.phenix.xglobal.ctx.core.IGlobalCapability
import com.phenix.xglobal.ctx.core.IGlobalContext
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
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val uiMode by UiModeKey.collectAsState(ctx)
                        val language by LanguageKey.collectAsState(ctx)

                        Text("UiMode: $uiMode", style = MaterialTheme.typography.headlineSmall)
                        Text("Language: ${language.displayLanguage}", style = MaterialTheme.typography.headlineSmall)

                        HorizontalDivider()
                        CapabilityToggles(ctx, applicationContext)
                        HorizontalDivider()

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
private fun DemoThemeFromGlobal(ctx: IGlobalContext, content: @Composable () -> Unit) {
    val uiMode by UiModeKey.collectAsState(ctx)
    androidx.compose.material3.MaterialTheme(content = content)
    if (uiMode == UiMode.UNSPECIFIED) return // 仅为展示读取 API；真实主题接线由 MaterialTheme 处理
}

/**
 * 能力勾选测试区：勾选即 register 激活对应能力（触发 onAttach：注册系统监听并读取初始值），
 * 展示其状态流实时值；取消勾选 unregister 释放。
 */
@Composable
private fun CapabilityToggles(ctx: IGlobalContext, appContext: Context) {
    Text("Capabilities（勾选启用，取消释放）", style = MaterialTheme.typography.titleMedium)

    val checked = remember { mutableStateMapOf<String, Boolean>() }
    // 已激活的能力实例：unregister 需要同一实例
    val activated = remember { mutableMapOf<String, IGlobalCapability>() }

    fun toggle(id: String, on: Boolean, create: () -> IGlobalCapability) {
        checked[id] = on
        if (on) {
            activated[id] = create().also { ctx.register(it) }
        } else {
            activated.remove(id)?.let { ctx.unregister(it) }
        }
    }

    // 各能力状态流直接经 StateKey 订阅；勾选后才 collect，未勾选不激活
    val networkOn = checked["xglobal.network"] == true
    val networkType = if (networkOn) NetworkCapability.TypeKey.collectAsState(ctx).value else null
    val networkBars = if (networkOn) NetworkCapability.BarsKey.collectAsState(ctx).value else null
    ToggleRow(
        "Network",
        networkOn,
        networkType?.let { "Network: $it bars=$networkBars" },
    ) { on ->
        toggle("xglobal.network", on) { NetworkCapability(ctx, appContext) }
    }

    val screenOn = checked["xglobal.screen"] == true
    val screenState = if (screenOn) ScreenCapability.StateKeyToken.collectAsState(ctx).value else null
    ToggleRow("Screen", screenOn, screenState?.let { "Screen: $it" }) { on ->
        toggle("xglobal.screen", on) { ScreenCapability(ctx, appContext) }
    }

    val batteryOn = checked["xglobal.battery"] == true
    val batteryLevel = if (batteryOn) BatteryCapability.LevelKey.collectAsState(ctx).value else null
    val batteryCharging = if (batteryOn) BatteryCapability.ChargingKey.collectAsState(ctx).value else null
    val batteryPowerSave = if (batteryOn) BatteryCapability.PowerSaveKey.collectAsState(ctx).value else null
    ToggleRow(
        "Battery",
        batteryOn,
        batteryLevel?.let { "Battery: $it% charging=$batteryCharging powerSave=$batteryPowerSave" },
    ) { on ->
        toggle("xglobal.battery", on) { BatteryCapability(ctx, appContext) }
    }

    val storageOn = checked["xglobal.storage"] == true
    val storageBytes = if (storageOn) StorageCapability.AvailableBytesKey.collectAsState(ctx).value else null
    ToggleRow(
        "Storage",
        storageOn,
        storageBytes?.let { "Storage: ${if (it >= 0) it / (1024 * 1024 * 1024) else -1} GB free" },
    ) { on ->
        toggle("xglobal.storage", on) { StorageCapability(ctx, appContext) }
    }

    val tzOn = checked["xglobal.timezone"] == true
    val tzId = if (tzOn) TimeZoneCapability.IdKey.collectAsState(ctx).value else null
    ToggleRow("TimeZone", tzOn, tzId?.let { "TimeZone: $it" }) { on ->
        toggle("xglobal.timezone", on) { TimeZoneCapability(ctx, appContext) }
    }

    val fontOn = checked["xglobal.fontscale"] == true
    val fontScale = if (fontOn) FontScaleCapability.ScaleKey.collectAsState(ctx).value else null
    ToggleRow("FontScale", fontOn, fontScale?.let { "FontScale: $it" }) { on ->
        toggle("xglobal.fontscale", on) { FontScaleCapability(ctx, appContext) }
    }
}

/** 单个能力行：勾选框 + 激活后的实时状态文本。 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    status: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** demo 内访问入口（真实 SDK 场景即 AppGlobalContext.require()）。 */
private object AppGlobalContextAccess {
    fun require(): IGlobalContext = com.phenix.xglobal.ctx.android.AppGlobalContext.require()
}

/** 三方自定义状态键示例：挂载自己的全局状态。 */
object DemoCounterKey : StateKey<Int>("demo.counter", 0)
