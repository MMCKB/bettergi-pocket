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

    /** 屏幕物理尺寸（像素）；失败返回 null */
    fun screenSize(): Pair<Int, Int>? = bridge.screenSize()

    /** 指定包名进程是否存活（用于判断目标/游戏是否在运行） */
    fun isProcessRunning(packageName: String): Boolean = bridge.pidOf(packageName) != null
}