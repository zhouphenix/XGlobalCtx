# XGlobalCtx

XGlobalCtx 是一套面向 **SDK 场景** 的全局上下文库：管理和挂载 App 进程生命周期内唯一的状态、事件与能力，开箱支持 **UiMode、系统语言、前后台**，并提供统一的扩展挂载机制。

- 同时支持 **View 体系** 与 **Jetpack Compose**
- 零第三方依赖（无 Hilt/Koin），不要求三方修改其 Application
- 非粘性事件 + 同步可取的状态（"主动 get 一次"即可）
- 仅主进程；UiMode / 语言只读系统值，不持久化、不覆盖

详细用法见 [docs/USAGE.md](docs/USAGE.md)。

## Roadmap / Future Features

以下为规划中的 App 相关功能，均以 **Capability 扩展** 形式按需挂载，不强制依赖：

- [ ] **网络状态能力**（`xglobal.network`）：监听 Connectivity 网络类型（WiFi / 蜂窝 / 无网）与变化事件，供 SDK 做降级、重试策略
- [ ] **屏幕状态能力**（`xglobal.screen`）：屏幕亮灭、锁屏解锁、用户在场（User Present）状态
- [ ] **剪贴板能力**（`xglobal.clipboard`）：剪贴板内容读取（Android 10+ 受限，仅前台窗口）与变更监听
- [ ] **电池与低电状态**（`xglobal.battery`）：电量、充电状态、省电模式监听，供 SDK 降低采样/上报频率
- [ ] **存储状态能力**（`xglobal.storage`）：内部/外部存储可用空间监听与低存储告警事件
- [ ] **时区与时间变化**（`xglobal.timezone`）：时区切换、时间同步事件（当前仅随 `onConfigurationChanged` 感知）
- [ ] **字体缩放能力**（`xglobal.fontscale`）：系统字体大小/显示尺寸变化监听，辅助无障碍适配
- [ ] **App 生命周期细化事件**（`xglobal.activity`）：Activity created / resumed / paused 计数与事件广播（当前 LifecycleCallbacks 未对外暴露细粒度事件）
- [ ] **多进程支持**：突破当前"仅主进程"边界，支持子进程数据同步（如 Broadcast / Binder 通道）
- [ ] **状态持久化（可选）**：为 `StateStore` 提供可选的持久化后端（DataStore / MMKV），当前设计为只读系统值不持久化
- [ ] **in-app 覆盖 UiMode / 语言**：提供进程内覆盖系统值的能力（配合 `AppCompatDelegate.setApplicationLocales` 或 recreate 策略）
- [ ] **Tracing / 调试面板**：Capability 挂载/卸载与状态变化的结构化日志，便于接入方排查
- [ ] **KMP / Desktop 扩展**：core 已是纯 Kotlin，可增加 JVM / iOS 目标与对应平台 Capability 实现
