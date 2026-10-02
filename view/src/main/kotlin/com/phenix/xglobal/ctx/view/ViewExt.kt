package com.phenix.xglobal.ctx.view

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phenix.xglobal.ctx.android.AppGlobalContext
import com.phenix.xglobal.ctx.core.EventBus
import com.phenix.xglobal.ctx.core.GlobalContext
import com.phenix.xglobal.ctx.core.StateKey
import kotlinx.coroutines.launch

/**
 * View 体系扩展：以生命周期安全的方式观察状态与事件。
 * 内部使用 repeatOnLifecycle(STARTED)，随生命周期自动开始/停止收集，无需手动取消。
 */

/** 观察状态键的值变化，STARTED 时收集、STOPPED 时停止。 */
public fun <T> StateKey<T>.observe(
    owner: LifecycleOwner,
    context: GlobalContext = AppGlobalContext.require(),
    block: (T) -> Unit,
) {
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            context.store.flow(this@observe).collect { block(it) }
        }
    }
}

/** 观察全局事件（非粘性：仅收到订阅之后的事件）。 */
public fun EventBus.observeEvents(
    owner: LifecycleOwner,
    context: GlobalContext = AppGlobalContext.require(),
    block: (Any) -> Unit,
) {
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { block(it) }
        }
    }
}
