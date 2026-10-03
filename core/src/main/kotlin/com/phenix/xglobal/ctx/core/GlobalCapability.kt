package com.phenix.xglobal.ctx.core

/**
 * 能力挂载接口：所有全局能力（内置的 UiMode/Language/Foreground，以及三方扩展）
 * 走同一套生命周期约定。
 *
 * 回调均在主线程触发；core 层不感知 Android 类型，
 * 系统配置等由 android 适配层转换后转发。
 */
public interface IGlobalCapability {
    /** 能力唯一标识，建议形如 "xglobal.uimode"、"myapp.session"。 */
    public val id: String

    /** 挂载时回调，可在此读取初始值、注册监听。 */
    public fun onAttach(context: IGlobalContext) {}

    /** App 回到前台。 */
    public fun onForeground() {}

    /** App 退到后台。 */
    public fun onBackground() {}

    /** 平台配置变化（android 层转发 onConfigurationChanged；core 层仅声明语义）。 */
    public fun onConfigurationChanged() {}

    /** 卸载或进程退出前的清理回调。 */
    public fun onDetach() {}
}
