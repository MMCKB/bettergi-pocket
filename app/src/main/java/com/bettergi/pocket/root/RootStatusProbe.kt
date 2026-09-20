package com.bettergi.pocket.root

/**
 * root 状态感知：经 [RootBridge] 查询系统状态。
 * 替代无障碍版里基于 AccessibilityEvent 的前台包名判断。
 */
class RootStatusProbe(
    private val bridge: RootBridge = RootBridge,
) {

    /** 当前前台包名；查询失败或未知返回 null */
    fun foregroundPackage(): String? = bridge.foreground()

}