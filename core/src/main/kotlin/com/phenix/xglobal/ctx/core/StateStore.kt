package com.phenix.xglobal.ctx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 类型安全的状态键。每个键携带默认值，保证 [IStateStore.get] 永远返回非空值。
 *
 * 推荐以 object 单例方式声明：
 * ```
 * object UiModeKey : StateKey<UiMode>("xglobal.uimode", UiMode.UNSPECIFIED)
 * ```
 */
public open class StateKey<T>(public val id: String, public val default: T) {
    override fun equals(other: Any?): Boolean = other is StateKey<*> && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "StateKey($id)"
}

/**
 * 状态仓库：进程内唯一状态的同步读写 + 响应式订阅。
 *
 * - [get] 任意线程可调，永远返回非空值（键携带默认值）
 * - [flow] 返回 [StateFlow]，相同值重复写入不会触发下游发射
 * - [set] 面向 SDK 内部与 Capability 实现，建议三方可通过自身 Capability 封装写入口
 */
public interface IStateStore {
    /** 同步读取当前值，任何时候可调（含未挂载 UI 的场景）。 */
    public fun <T> get(key: StateKey<T>): T

    /** 响应式订阅该键的值变化。 */
    public fun <T> flow(key: StateKey<T>): StateFlow<T>

    /** 写入新值。相同值写入无副作用。 */
    public fun <T> set(key: StateKey<T>, value: T)
}

/**
 * 内核实现，纯 Kotlin、无 Android 依赖。
 *
 * 线程安全：基于 kotlinx.coroutines 的线程安全实现；写入与读取无自定义锁。
 */
internal class StateStoreImpl : IStateStore {

    private val flows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flowFor(key: StateKey<T>): MutableStateFlow<T> =
        flows.getOrPut(key.id) { kotlinx.coroutines.flow.MutableStateFlow(key.default) } as MutableStateFlow<T>

    override fun <T> get(key: StateKey<T>): T = flowFor(key).value

    override fun <T> flow(key: StateKey<T>): StateFlow<T> = flowFor(key)

    override fun <T> set(key: StateKey<T>, value: T) {
        flowFor(key).value = value
    }
}
