package com.phenix.xglobal.ctx.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 非粘性事件总线。
 *
 * - 订阅方只收到订阅之后发生的事件，不回放历史（"当前值"语义请走 [IStateStore.get]）
 * - 事件类型为 [Any]，完全开放，不限制业务；订阅侧用 `filterIsInstance` / `is` 分发
 */
public interface IEventBus {
    /** 所有事件的流；非粘性，缓冲满时丢弃最旧事件。 */
    public val events: SharedFlow<Any>

    /** 挂起发射：缓冲满时挂起等待。 */
    public suspend fun post(event: Any)

    /** 非挂起发射：缓冲满时丢弃最旧事件并返回 true（尽力送达）。 */
    public fun tryPost(event: Any): Boolean

    /**
     * 粘性发射语法糖：事件广播给当前订阅者，同时把最近一条挂到 [key] 下——
     * 之后任何时刻（含订阅方尚未创建时）都可经 [stickyEvent] / [observeSticky]
     * 取回，新发布覆盖旧值。适合"最新一条事件需要错过补偿"的场景；
     * 纯瞬态通知请用 [post] / [tryPost]（不产生粘性）。
     */
    public fun postSticky(event: Any, key: String = event::class.java.name)

    /** 最近一条粘性事件；从未发布过为 null。 */
    public fun stickyEvent(key: String): Any?

    /** 订阅某 key 的粘性事件流（replay=1：新订阅者立即得到最近一条）。 */
    public fun observeSticky(key: String): StateFlow<Any?>

    /** 清除某 key 的粘性事件。 */
    public fun removeSticky(key: String)
}

/**
 * 内核实现，纯 Kotlin、无 Android 依赖。
 */
internal class EventBusImpl : IEventBus {

    private val mutable: MutableSharedFlow<Any> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // 粘性槽位:每 key 一个 replay=1 的流,postSticky 写入,observeSticky 订阅
    private val sticky = ConcurrentHashMap<String, MutableStateFlow<Any?>>()

    override val events: SharedFlow<Any> = mutable

    override suspend fun post(event: Any) {
        mutable.emit(event)
    }

    override fun tryPost(event: Any): Boolean = mutable.tryEmit(event)

    override fun postSticky(event: Any, key: String) {
        flowFor(key).value = event
        mutable.tryEmit(event)
    }

    override fun stickyEvent(key: String): Any? = flowFor(key).value

    override fun observeSticky(key: String): StateFlow<Any?> = flowFor(key)

    override fun removeSticky(key: String) {
        flowFor(key).value = null
    }

    private fun flowFor(key: String): MutableStateFlow<Any?> =
        sticky.getOrPut(key) { MutableStateFlow(null) }

    private companion object {
        const val DEFAULT_BUFFER = 64
    }
}
