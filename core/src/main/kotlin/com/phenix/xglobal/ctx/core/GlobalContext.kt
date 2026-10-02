package com.phenix.xglobal.ctx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局上下文门面：状态仓库 + 事件总线 + 能力注册表 + 前后台状态。
 *
 * core 层为纯 Kotlin 接口；Android 侧由 android 模块提供单例实现与初始化。
 */
public interface GlobalContext {
    /** 状态仓库：同步读 + 响应式订阅。 */
    public val store: StateStore

    /** 事件总线：非粘性。 */
    public val bus: EventBus

    /** 是否处于前台（无系统回调来源时由平台层驱动）。 */
    public val isForeground: Boolean

    /** 前后台状态的响应式形式。 */
    public val foregroundFlow: StateFlow<Boolean>

    /** 按 id 获取已挂载能力，未挂载时抛 [NoSuchElementException]。 */
    public fun <T : GlobalCapability> getCapability(id: String): T

    /** 挂载能力，重复注册同一 id 无副作用；触发 [GlobalCapability.onAttach]。 */
    public fun register(capability: GlobalCapability)

    /** 卸载能力；未注册时无副作用。触发 [GlobalCapability.onDetach]。 */
    public fun unregister(capability: GlobalCapability)
}

/**
 * 内核实现。
 *
 * 纯 Kotlin、无 Android 依赖；平台适配层（如 android 模块）持有本实现，
 * 并通过 [notifyForeground] / [notifyBackground] / [notifyConfigurationChanged] /
 * [terminate] 转发系统回调。
 */
public class GlobalContextImpl : GlobalContext {

    override val store: StateStore = StateStoreImpl()
    override val bus: EventBus = EventBusImpl()

    private val registry = LinkedHashMap<String, GlobalCapability>()
    private val _foregroundFlow = MutableStateFlow(false)
    override val foregroundFlow: StateFlow<Boolean> = _foregroundFlow
    override val isForeground: Boolean get() = _foregroundFlow.value

    @Suppress("UNCHECKED_CAST")
    override fun <T : GlobalCapability> getCapability(id: String): T =
        registry[id] as? T
            ?: throw NoSuchElementException("Capability not registered: $id")

    override fun register(capability: GlobalCapability) {
        if (registry.containsKey(capability.id)) return
        registry[capability.id] = capability
        capability.onAttach(this)
        if (_foregroundFlow.value) capability.onForeground()
    }

    override fun unregister(capability: GlobalCapability) {
        if (registry.remove(capability.id) != null) {
            capability.onDetach()
        }
    }

    // ---- 平台层回调入口（由 android 等平台适配层调用；三方业务不应直接使用） ----

    public fun notifyForeground() {
        if (_foregroundFlow.value) return
        _foregroundFlow.value = true
        registry.values.forEach { it.onForeground() }
    }

    public fun notifyBackground() {
        if (!_foregroundFlow.value) return
        _foregroundFlow.value = false
        registry.values.forEach { it.onBackground() }
    }

    public fun notifyConfigurationChanged() {
        registry.values.forEach { it.onConfigurationChanged() }
    }

    public fun terminate() {
        registry.values.forEach { it.onDetach() }
        registry.clear()
    }
}
