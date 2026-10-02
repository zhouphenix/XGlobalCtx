package com.phenix.xglobal.ctx.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phenix.xglobal.ctx.android.AppGlobalContext
import com.phenix.xglobal.ctx.core.GlobalContext
import com.phenix.xglobal.ctx.core.StateKey

/**
 * Compose 扩展：状态收集为 Compose State，随生命周期自动订阅/退订。
 */

/** 把状态键的值收集为 [State]，内部使用 collectAsStateWithLifecycle。 */
@Composable
public fun <T> StateKey<T>.collectAsState(
    context: GlobalContext = AppGlobalContext.require(),
): State<T> = context.store.flow(this).collectAsStateWithLifecycle()

/**
 * 一次性收集全局事件（非粘性：仅收到组合进入后发射的事件）。
 * 组合离开时自动取消。
 */
@Composable
public fun CollectEvents(
    context: GlobalContext = AppGlobalContext.require(),
    block: (Any) -> Unit,
) {
    LaunchedEffect(context) {
        context.bus.events.collect { block(it) }
    }
}
