# XGlobalCtx 使用文档

XGlobalCtx 是一套面向 **SDK 场景** 的全局上下文库：管理和挂载 App 进程生命周期内唯一的状态、事件与能力，开箱支持 **UiMode、系统语言、前后台**，并提供统一的扩展挂载机制。

- 同时支持 **View 体系** 与 **Jetpack Compose**
- 零第三方依赖（无 Hilt/Koin），不要求三方修改其 Application
- 非粘性事件 + 同步可取的状态（"主动 get 一次"即可）
- 仅主进程；UiMode / 语言只读系统值，不持久化、不覆盖

---

## 1. 模块与依赖

| 模块 | 内容 | 是否必选 |
|---|---|---|
| `core` | 纯 Kotlin 内核：StateStore / EventBus / Capability / GlobalContext 接口 | 必选（随 android 传递） |
| `android` | Android 实现：初始化、系统回调、内置 Capability（UiMode / Language / Foreground） | 必选 |
| `view` | View 体系扩展：`observe` 系列扩展函数 | 用 View 时添加 |
| `compose` | Compose 扩展：`collectAsState` 系列 | 用 Compose 时添加 |

```kotlin
dependencies {
    implementation("com.xxx.xglobalctx:android:1.0.0")
    // 按需添加其一或多个
    implementation("com.xxx.xglobalctx:view:1.0.0")
    implementation("com.xxx.xglobalctx:compose:1.0.0")
}
```

## 2. 初始化

默认**无需任何手动初始化**：库通过内置 `ContentProvider`（`CtxAutoInitProvider`）在进程启动时自动完成初始化。

以下情况需要手动初始化（在 Application `onCreate` 中，早于任何访问）：

- 三方打包/合规工具移除了自动初始化组件
- 多进程 App（本库仅主进程生效，建议在主进程手动 init，其他进程不访问）

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGlobalContext.init(this)   // 幂等，重复调用无副作用
    }
}
```

> 访问入口统一为 `AppGlobalContext.require()`：未初始化时抛出带明确说明的异常（而不是静默 NPE）。

---

## 3. 快速开始

### 3.1 同步读取（任意位置，主动 get 一次）

```kotlin
val ctx = AppGlobalContext.require()

val uiMode = ctx.uiMode       // UiMode.DARK / LIGHT / UNSPECIFIED
val lang = ctx.language       // Locale，系统当前语言
val foreground = ctx.isForeground
```

### 3.2 响应式订阅（状态变化时刷新）

```kotlin
// 不依赖 UI 层，任意协程作用域内
lifecycleScope.launch {
    ctx.store.flow(UiModeKey).collect { uiMode ->
        // UiMode 变化
    }
}
```

---

## 4. View 体系用法（`view` 模块）

```kotlin
// 观察状态：内部使用 repeatOnLifecycle(STARTED)，自动随生命周期取消
UiModeKey.observe(this) { uiMode ->
    textView.setTextAppearance(
        if (uiMode == UiMode.DARK) R.style.TextDark else R.style.TextLight
    )
}

LanguageKey.observe(this) { locale ->
    titleView.text = locale.displayLanguage
}

// 观察事件
ctx.bus.observeEvents(this) { event ->
    when (event) {
        is ThemeChangedEvent -> { /* ... */ }
        else -> { /* 三方可自定义任意事件类型 */ }
    }
}
```

## 5. Compose 用法（`compose` 模块）

```kotlin
@Composable
fun ThemeText() {
    val uiMode by UiModeKey.collectAsState()
    val lang by LanguageKey.collectAsState()

    Text(
        text = lang.displayLanguage,
        color = if (uiMode == UiMode.DARK) Color.White else Color.Black
    )
}

// 一次性事件收集
@Composable
fun EventWatcher() {
    CollectEvents { event ->
        // 注意：Compose 内不做粘性处理，仅收到订阅后的事件
    }
}
```

---

## 6. 核心 API 一览

### GlobalContext（门面）

```kotlin
interface GlobalContext {
    val store: StateStore            // 状态仓库
    val bus: EventBus                // 事件总线（非粘性）
    val isForeground: Boolean        // 前后台快捷属性

    fun <T : GlobalCapability> getCapability(id: String): T
    fun register(capability: GlobalCapability)     // 挂载扩展
    fun unregister(capability: GlobalCapability)   // 卸载扩展
}
```

### StateStore（状态仓库）

```kotlin
class StateKey<T>(val id: String, val default: T)   // 类型令牌，编译期类型安全

interface StateStore {
    fun <T> get(key: StateKey<T>): T              // 同步读，任何线程可调
    fun <T> flow(key: StateKey<T>): StateFlow<T>  // 响应式订阅
    fun <T> set(key: StateKey<T>, value: T)       // SDK/能力内部使用
}
```

要点：

- `get` 永远返回非空值（`StateKey` 携带 default），无可空传染
- 相同值重复 `set` 不会触发下游发射（`StateFlow` 天然去重）
- `set` 主要面向 SDK 内部与 Capability 实现；三方建议通过自身 Capability 封装写入口，避免直接改全局状态

### EventBus（事件总线）

```kotlin
interface EventBus {
    val events: SharedFlow<Any>       // 非粘性：只收到订阅之后的事件
    suspend fun post(event: Any)      // 挂起发射
    fun tryPost(event: Any): Boolean  // 非挂起发射，缓冲满时返回 false
}
```

要点：

- **非粘性**：当前值不通过事件获取，一律走 `StateStore.get`
- 事件类型为 `Any`，完全开放，不限制业务；订阅侧用 `is` 分发

### GlobalCapability（扩展挂载）

```kotlin
interface GlobalCapability {
    val id: String
    fun onAttach(context: GlobalContext) {}                   // 挂载时回调
    fun onForeground() {}                                     // App 回到前台
    fun onBackground() {}                                     // App 退到后台
    fun onConfigurationChanged(config: Configuration) {}      // 系统配置变化
    fun onDetach() {}                                         // 卸载/进程退出
}
```

### 内置 Capability 与快捷属性

| 名称 | id | 访问方式 | 说明 |
|---|---|---|---|
| UiModeCapability | `xglobal.uimode` | `ctx.uiMode` / `ctx.store.flow(UiModeKey)` | 深浅色，跟随系统 |
| LanguageCapability | `xglobal.language` | `ctx.language` / `ctx.store.flow(LanguageKey)` | 只读系统语言 |
| ForegroundCapability | `xglobal.foreground` | `ctx.isForeground` | 驱动前后台回调 |

内置能力与三方扩展走**同一套** `GlobalCapability` 机制，由库自举挂载。

---

## 7. 扩展：挂载自定义能力

适合放置"App 全生命周期唯一"的功能：登录态、用户信息、日志开关、性能采样开关等。

```kotlin
class SessionCapability : GlobalCapability {
    override val id = "myapp.session"

    private lateinit var ctx: GlobalContext

    // 自定义状态键
    private val loggedInKey = StateKey("myapp.session.logged_in", false)

    override fun onAttach(context: GlobalContext) {
        ctx = context
        // 可在此读取一次初始值 / 注册监听
    }

    override fun onForeground() {
        // App 回前台，例如刷新 token
    }

    // 对外暴露只读 API（避免外界直接写全局状态）
    fun isLoggedIn(): Boolean = ctx.store.get(loggedInKey)
    fun loggedInFlow(): StateFlow<Boolean> = ctx.store.flow(loggedInKey)

    // 写入口：由业务显式调用
    fun onLoginSuccess() {
        ctx.store.set(loggedInKey, true)
        ctx.bus.tryPost(SessionEvent.LOGGED_IN)
    }

    override fun onDetach() { /* 清理 */ }
}
```

挂载与卸载：

```kotlin
val session = SessionCapability()
AppGlobalContext.register(session)     // 触发 onAttach
// ...
AppGlobalContext.unregister(session)   // 触发 onDetach
```

访问：

```kotlin
val ctx = AppGlobalContext.require()
val session = ctx.getCapability<SessionCapability>("myapp.session")
if (session.isLoggedIn()) { /* ... */ }
```

### 发送自定义事件

```kotlin
// 定义（任意类型即可）
data class SessionEvent(val type: String)

// 发送
AppGlobalContext.require().bus.tryPost(SessionEvent("logged_in"))

// 接收
ctx.bus.events
    .filterIsInstance<SessionEvent>()
    .collect { /* ... */ }
```

---

## 8. 线程与生命周期约定

- 所有系统回调（配置变化、前后台）在库内部协程作用域（`SupervisorJob + Dispatchers.Main.immediate`）中处理后再写入 Store，读侧无锁
- 内核作用域为进程级，不随任何 Activity 销毁
- `StateStore.get` 线程安全；事件发射推荐在主线程或协程中调用
- UI 层订阅（view / compose 模块）自动跟随生命周期，无需手动取消

## 9. 行为边界（第一版）

- **仅主进程**：多进程场景下非主进程不应访问本库
- **UiMode / 语言只读**：跟随系统，不持久化、不提供覆盖能力；进程启动时读取一次，之后由 `onConfigurationChanged` 驱动刷新
- **事件非粘性**：不保存历史事件；需要"当前值"一律用 StateStore
- **未初始化访问**：抛出明确异常，请确保访问发生在 `init` 或自动初始化之后
