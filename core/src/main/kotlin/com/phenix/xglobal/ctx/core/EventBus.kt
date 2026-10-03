package com.phenix.xglobal.ctx.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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

    override val events: SharedFlow<Any> = mutable

    override suspend fun post(event: Any) {
        mutable.emit(event)
    }

    override fun tryPost(event: Any): Boolean = mutable.tryEmit(event)

    private companion object {
        const val DEFAULT_BUFFER = 64
    }
}
