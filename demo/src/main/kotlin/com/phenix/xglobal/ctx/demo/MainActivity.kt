package com.phenix.xglobal.ctx.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phenix.xglobal.ctx.android.BatteryCapability
import com.phenix.xglobal.ctx.android.FontScaleCapability
import com.phenix.xglobal.ctx.android.ForegroundCapability
import com.phenix.xglobal.ctx.android.LanguageCapability
import com.phenix.xglobal.ctx.android.LanguageKey
import com.phenix.xglobal.ctx.android.NetworkCapability
import com.phenix.xglobal.ctx.android.ScreenCapability
import com.phenix.xglobal.ctx.android.SplitScreenCapability
import com.phenix.xglobal.ctx.android.StorageCapability
import com.phenix.xglobal.ctx.android.SystemBarsCapability
import com.phenix.xglobal.ctx.android.TimeZoneCapability
import com.phenix.xglobal.ctx.android.UiMode
import com.phenix.xglobal.ctx.android.UiModeCapability
import com.phenix.xglobal.ctx.android.UiModeKey
import com.phenix.xglobal.ctx.compose.collectAsState
import com.phenix.xglobal.ctx.core.IGlobalCapability
import com.phenix.xglobal.ctx.core.IGlobalContext
import com.phenix.xglobal.ctx.core.StateKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/**
 * Compose 用法示例：所有能力（含内置 UiMode/Language/Foreground）默认不启用，
 * 通过勾选 register 激活、取消勾选 unregister 释放。
 * 变化即时反映在行内状态文本;非实时/跨界面可见的状态（前后台、分屏、系统栏、手动查询）
 * 用应用内 flash 反馈条展示（系统 Toast 可能被用户关闭通知而抑制,不可依赖）。
 * 每个能力行有独立折叠按钮,控制项/监听入口在各自折叠区内。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = AppGlobalContextAccess.require()
        setContent {
            DemoThemeFromGlobal(ctx) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val flash = remember { mutableStateOf<String?>(null) }
                    // flash 反馈条自动消失
                    LaunchedEffect(flash.value) {
                        flash.value?.let {
                            delay(2_500)
                            flash.value = null
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Capabilities（勾选启用，取消释放）",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            CapabilityToggles(ctx, this@MainActivity) { flash.value = it }
                            HorizontalDivider()
                            Button(onClick = { startActivity(Intent(this@MainActivity, ViewDemoActivity::class.java)) }) {
                                Text("Open View demo")
                            }
                        }
                        // 应用内 flash 反馈条（替代被系统抑制的 Toast）
                        flash.value?.let { msg ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(24.dp),
                                shape = MaterialTheme.shapes.medium,
                                tonalElevation = 6.dp,
                                shadowElevation = 6.dp,
                            ) {
                                Text(
                                    msg,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
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
    if (uiMode == UiMode.UNSPECIFIED) return // 未启用 UiMode 能力时为默认值;真实主题接线由 MaterialTheme 处理
}

/** 全部可勾选能力的 id(用于重建后从注册表恢复勾选)。 */
private val capabilityIds = listOf(
    "xglobal.uimode", "xglobal.language", "xglobal.foreground",
    "xglobal.network", "xglobal.screen", "xglobal.battery",
    "xglobal.storage", "xglobal.timezone", "xglobal.fontscale",
    "xglobal.splitscreen", "xglobal.systembars",
)

/**
 * 能力勾选列表：每行独立折叠。勾选即 register 激活（触发 onAttach）,
 * 折叠区内放该能力的控制按钮 / 手动查询 / 监听说明。
 */
@Composable
private fun CapabilityToggles(ctx: IGlobalContext, activity: Activity, onFlash: (String) -> Unit) {
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    // 已激活的能力实例：unregister 需要同一实例
    val activated = remember { mutableMapOf<String, IGlobalCapability>() }

    // Activity 重建（如系统深浅色/语言切换触发 configuration change）后,
    // 从进程级注册表恢复勾选与激活实例(注册表不随 Activity 销毁)
    remember {
        capabilityIds.forEach { id ->
            if (ctx.isRegistered(id)) {
                checked[id] = true
                activated[id] = ctx.getCapability<IGlobalCapability>(id)
            }
        }
        true // 只在首次组合执行一次
    }
    // 重建后 SystemBars 的宿主指向已销毁的旧 Activity,需重绑到新 Activity(重建 scrim/insets 接线)
    SideEffect {
        if (checked["xglobal.systembars"] == true) {
            (activated["xglobal.systembars"] as? SystemBarsCapability)?.attachHost(activity)
        }
    }

    fun toggle(
        id: String,
        on: Boolean,
        create: () -> IGlobalCapability,
        cleanup: ((IGlobalCapability) -> Unit)? = null,
    ) {
        checked[id] = on
        if (on) {
            activated[id] = create().also { ctx.register(it) }
        } else {
            activated.remove(id)?.let {
                cleanup?.invoke(it)
                ctx.unregister(it)
            }
        }
    }

    // ---- 内置能力:UiMode / Language / Foreground(默认不启用) ----
    val uiModeOn = checked["xglobal.uimode"] == true
    val uiMode = if (uiModeOn) UiModeKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "UiMode", uiModeOn, uiMode?.let { "UiMode: $it" },
        onCheckedChange = { on -> toggle("xglobal.uimode", on, create = { UiModeCapability(ctx, activity) }) },
    ) {
        QueryButton("查询当前值", uiMode?.let { "UiMode: $it" } ?: "UiMode: 未知", onFlash)
    }

    val languageOn = checked["xglobal.language"] == true
    val language = if (languageOn) LanguageKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "Language", languageOn, language?.let { "Language: ${it.displayLanguage}" },
        onCheckedChange = { on -> toggle("xglobal.language", on, create = { LanguageCapability(ctx, activity) }) },
    ) {
        QueryButton("查询当前值", language?.let { "Language: ${it.displayLanguage}" } ?: "Language: 未知", onFlash)
    }

    val fgOn = checked["xglobal.foreground"] == true
    val fg = if (fgOn) ctx.foregroundFlow.collectAsState().value else null
    ExpandableCapabilityRow(
        "Foreground", fgOn, fg?.let { "Foreground: ${if (it) "前台" else "后台"}" },
        onCheckedChange = { on -> toggle("xglobal.foreground", on, create = { ForegroundCapability(ctx) }) },
    ) {
        QueryButton("查询当前值", "Foreground: ${if (ctx.isForeground) "前台" else "后台"}", onFlash)
    }
    // 前后台切换 flash 反馈(退后台后 UI 不可见,flash 常驻应用内可见;跳过启用时的初值)
    if (fgOn) {
        LaunchedEffect(Unit) {
            ctx.foregroundFlow.drop(1).collect { isForeground ->
                onFlash(if (isForeground) "App 进入前台" else "App 退到后台")
            }
        }
    }

    // ---- 设备/系统状态能力 ----
    val networkOn = checked["xglobal.network"] == true
    val networkType = if (networkOn) NetworkCapability.TypeKey.collectAsState(ctx).value else null
    val networkBars = if (networkOn) NetworkCapability.BarsKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "Network", networkOn, networkType?.let { "Network: $it bars=$networkBars" },
        onCheckedChange = { on -> toggle("xglobal.network", on, create = { NetworkCapability(ctx, activity) }) },
    ) {
        QueryButton("查询当前值", networkType?.let { "Network: $it bars=$networkBars" } ?: "Network: 未知", onFlash)
    }

    val screenOn = checked["xglobal.screen"] == true
    val screenState = if (screenOn) ScreenCapability.StateKeyToken.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "Screen", screenOn, screenState?.let { "Screen: $it" },
        onCheckedChange = { on -> toggle("xglobal.screen", on, create = { ScreenCapability(ctx, activity) }) },
    ) {
        QueryButton("查询当前值", screenState?.let { "Screen: $it" } ?: "Screen: 未知", onFlash)
    }

    val batteryOn = checked["xglobal.battery"] == true
    val batteryLevel = if (batteryOn) BatteryCapability.LevelKey.collectAsState(ctx).value else null
    val batteryCharging = if (batteryOn) BatteryCapability.ChargingKey.collectAsState(ctx).value else null
    val batteryPowerSave = if (batteryOn) BatteryCapability.PowerSaveKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "Battery", batteryOn,
        batteryLevel?.let { "Battery: $it% charging=$batteryCharging powerSave=$batteryPowerSave" },
        onCheckedChange = { on -> toggle("xglobal.battery", on, create = { BatteryCapability(ctx, activity) }) },
    ) {
        QueryButton(
            "查询当前值",
            batteryLevel?.let { "Battery: $it% charging=$batteryCharging powerSave=$batteryPowerSave" }
                ?: "Battery: 未知",
            onFlash,
        )
    }

    val storageOn = checked["xglobal.storage"] == true
    val storageBytes = if (storageOn) StorageCapability.AvailableBytesKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "Storage", storageOn,
        storageBytes?.let { "Storage: ${if (it >= 0) it / (1024 * 1024 * 1024) else -1} GB free" },
        onCheckedChange = { on -> toggle("xglobal.storage", on, create = { StorageCapability(ctx, activity) }) },
    ) {
        QueryButton(
            "查询当前值",
            storageBytes?.let { "Storage: ${if (it >= 0) it / (1024 * 1024 * 1024) else -1} GB free" }
                ?: "Storage: 未知",
            onFlash,
        )
    }

    val tzOn = checked["xglobal.timezone"] == true
    val tzId = if (tzOn) TimeZoneCapability.IdKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "TimeZone", tzOn, tzId?.let { "TimeZone: $it" },
        onCheckedChange = { on -> toggle("xglobal.timezone", on, create = { TimeZoneCapability(ctx, activity) }) },
    ) {
        QueryButton("查询当前值", tzId?.let { "TimeZone: $it" } ?: "TimeZone: 未知", onFlash)
    }

    val fontOn = checked["xglobal.fontscale"] == true
    val fontScale = if (fontOn) FontScaleCapability.ScaleKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "FontScale", fontOn, fontScale?.let { "FontScale: $it" },
        onCheckedChange = { on -> toggle("xglobal.fontscale", on, create = { FontScaleCapability(ctx, activity) }) },
    ) {
        QueryButton("查询当前值", fontScale?.let { "FontScale: $it" } ?: "FontScale: 未知", onFlash)
    }

    // ---- 分屏监测 ----
    val splitOn = checked["xglobal.splitscreen"] == true
    val inMultiWindow = if (splitOn) SplitScreenCapability.InMultiWindowKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "SplitScreen", splitOn, inMultiWindow?.let { "SplitScreen: ${if (it) "分屏中" else "非分屏"}" },
        onCheckedChange = { on -> toggle("xglobal.splitscreen", on, create = { SplitScreenCapability(ctx, activity) }) },
    ) {
        QueryButton(
            "查询当前值",
            "SplitScreen: ${if (ctx.store.get(SplitScreenCapability.InMultiWindowKey)) "分屏中" else "非分屏"}",
            onFlash,
        )
    }
    // 分屏进出 flash 反馈(跳过启用时的初值)
    if (splitOn) {
        LaunchedEffect(Unit) {
            ctx.store.flow(SplitScreenCapability.InMultiWindowKey).drop(1).collect { inMulti ->
                onFlash(if (inMulti) "进入分屏" else "退出分屏")
            }
        }
    }

    // ---- 系统栏控制/监听 ----
    val barsOn = checked["xglobal.systembars"] == true
    val statusVisible = if (barsOn) SystemBarsCapability.StatusBarVisibleKey.collectAsState(ctx).value else null
    val navVisible = if (barsOn) SystemBarsCapability.NavBarVisibleKey.collectAsState(ctx).value else null
    val statusFits = if (barsOn) SystemBarsCapability.StatusFitsKey.collectAsState(ctx).value else null
    val navFits = if (barsOn) SystemBarsCapability.NavFitsKey.collectAsState(ctx).value else null
    val barsIconDark = if (barsOn) SystemBarsCapability.IconDarkKey.collectAsState(ctx).value else null
    ExpandableCapabilityRow(
        "SystemBars", barsOn,
        barsIconDark?.let {
            "SystemBars: 状态栏=${if (statusVisible == true) "显" else "隐"} " +
                "底栏=${if (navVisible == true) "显" else "隐"} " +
                "fits=S:${if (statusFits == true) "开" else "关"}/N:${if (navFits == true) "开" else "关"} " +
                "icon=${if (barsIconDark == true) "dark" else "light"}"
        },
        onCheckedChange = { on ->
            toggle(
                "xglobal.systembars",
                on,
                { SystemBarsCapability(ctx, activity).also { it.attachHost(activity) } },
            ) { cap ->
                // 释放前恢复系统栏显隐与 fits,避免界面残留全屏状态
                cap as SystemBarsCapability
                cap.showSystemBars()
                cap.setFitsSystemWindows(false)
                cap.detachHost(activity)
            }
        },
    ) {
        // 控制台:作用于当前 Activity,所有变更以 flash 反馈;分区以分割线隔开
        val bars = ctx.getCapability<SystemBarsCapability>("xglobal.systembars")

        // ---- 显隐(每栏独立勾选) ----
        Text("显隐", style = MaterialTheme.typography.titleSmall)
        CheckboxRow("状态栏(顶栏)", statusVisible == true) { on ->
            if (on) bars.showStatusBar() else bars.hideStatusBar()
        }
        CheckboxRow("导航栏(底部栏)", navVisible == true) { on ->
            if (on) bars.showNavBar() else bars.hideNavBar()
        }
        HorizontalDivider()

        // ---- 外观 ----
        Text("外观", style = MaterialTheme.typography.titleSmall)
        CheckboxRow("状态栏避让(顶部 padding)", statusFits == true) { on ->
            bars.setStatusFits(on)
            onFlash("状态栏避让: ${if (on) "开" else "关"}")
        }
        CheckboxRow("导航栏避让(底部 padding)", navFits == true) { on ->
            bars.setNavFits(on)
            onFlash("导航栏避让: ${if (on) "开" else "关"}")
        }
        CheckboxRow("图标深色(浅色背景用)", barsIconDark == true) { on ->
            bars.setIconDark(on)
            onFlash("系统栏图标: ${if (on) "深色" else "浅色"}")
        }
        HorizontalDivider()

        // ---- 颜色拾取:状态栏/底部栏独立 ----
        Text("状态栏颜色", style = MaterialTheme.typography.titleSmall)
        BarColorPicker { r, g, b, a -> bars.setStatusBarBackground((r shl 16) or (g shl 8) or b, a) }
        HorizontalDivider()
        Text("底部栏颜色", style = MaterialTheme.typography.titleSmall)
        BarColorPicker { r, g, b, a -> bars.setNavBarBackground((r shl 16) or (g shl 8) or b, a) }
        HorizontalDivider()

        // ---- 一键演示(2 列网格) ----
        Text("一键演示", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoButton("一键全屏", Modifier.weight(1f)) {
                bars.hideStatusBar()
                bars.hideNavBar()
                bars.setFitsSystemWindows(false)
                bars.setWindowBackground(0xFF101010.toInt())
                bars.setIconDark(false)
                onFlash("演示: 全屏沉浸")
            }
            DemoButton("一键白天", Modifier.weight(1f)) {
                bars.showStatusBar()
                bars.showNavBar()
                bars.setStatusBarBackground(0xFFFFFFFF.toInt(), 1f)
                bars.setNavBarBackground(0xFFEEEEEE.toInt(), 1f)
                bars.setIconDark(true)
                bars.setWindowBackground(0xFFFFFFFF.toInt())
                onFlash("演示: 白天模式")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoButton("一键黑夜", Modifier.weight(1f)) {
                bars.showStatusBar()
                bars.showNavBar()
                bars.setStatusBarBackground(0xFF000000.toInt(), 1f)
                bars.setNavBarBackground(0xFF000000.toInt(), 1f)
                bars.setIconDark(false)
                bars.setWindowBackground(0xFF101010.toInt())
                onFlash("演示: 黑夜模式")
            }
            DemoButton("半透明栏", Modifier.weight(1f)) {
                bars.showStatusBar()
                bars.showNavBar()
                bars.setStatusBarBackground(0xFF3F51B5.toInt(), 0.5f)
                bars.setNavBarBackground(0xFF3F51B5.toInt(), 0.5f)
                onFlash("演示: 半透明系统栏")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoButton("恢复默认", Modifier.weight(1f)) {
                bars.showStatusBar()
                bars.showNavBar()
                bars.setStatusBarBackground(0xFF3F51B5.toInt(), 1f)
                bars.setNavBarBackground(0xFF3F51B5.toInt(), 1f)
                bars.setIconDark(false)
                bars.setWindowBackground(0xFFFFFFFF.toInt())
                onFlash("演示: 已恢复默认")
            }
        }
    }
    // 系统栏显隐 flash 反馈(按钮/系统手势唤出隐藏栏均触发;跳过启用时的初值)
    if (barsOn) {
        LaunchedEffect(Unit) {
            ctx.store.flow(SystemBarsCapability.VisibleKey).drop(1).collect { visible ->
                onFlash("系统栏: ${if (visible) "显示" else "隐藏"}")
            }
        }
    }
}

/** 单个能力行：勾选框 + 行内状态 + 右侧独立折叠按钮;折叠区放控制/查询项。 */
@Composable
private fun ExpandableCapabilityRow(
    label: String,
    checked: Boolean,
    status: String?,
    onCheckedChange: (Boolean) -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 ▲" else "展开 ▼")
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (checked) {
                    extra()
                } else {
                    Text("未启用：勾选后可查看与控制", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 折叠区内的手动查询按钮：点击以 flash 反馈当前状态。 */
@Composable
private fun QueryButton(text: String, message: String, onFlash: (String) -> Unit) {
    Button(onClick = { onFlash(message) }) { Text(text) }
}

/** 颜色拾取单通道滑杆(0..255)。 */
@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value / 255f,
        onValueChange = { onChange((it * 255).roundToInt()) },
    )
}

/** 勾选配置行。 */
@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 演示区按钮:等宽网格单元。 */
@Composable
private fun DemoButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier) { Text(text, maxLines = 1) }
}

/**
 * 单栏颜色拾取器:R/G/B + 透明度滑杆 + 实时预览色块,拖动即时回调。
 */
@Composable
private fun BarColorPicker(onChange: (r: Int, g: Int, b: Int, a: Float) -> Unit) {
    var r by remember { mutableStateOf(63) }
    var g by remember { mutableStateOf(81) }
    var b by remember { mutableStateOf(181) }
    var a by remember { mutableStateOf(1f) }

    fun apply() = onChange(r, g, b, a)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(androidx.compose.ui.graphics.Color(r / 255f, g / 255f, b / 255f, a))
        )
        Text(
            "#${"%02X%02X%02X".format(r, g, b)} A=${"%.0f".format(a * 100)}%",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    ColorSlider("R", r) { r = it; apply() }
    ColorSlider("G", g) { g = it; apply() }
    ColorSlider("B", b) { b = it; apply() }
    Text("透明度: ${"%.0f".format(a * 100)}%", style = MaterialTheme.typography.bodySmall)
    Slider(value = a, onValueChange = { a = it; apply() })
}

/** demo 内访问入口（真实 SDK 场景即 AppGlobalContext.require()）。 */
private object AppGlobalContextAccess {
    fun require(): IGlobalContext = com.phenix.xglobal.ctx.android.AppGlobalContext.require()
}

/** 三方自定义状态键示例：挂载自己的全局状态。 */
object DemoCounterKey : StateKey<Int>("demo.counter", 0)
