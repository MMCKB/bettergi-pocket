package com.bettergi.pocket.root

import com.bettergi.pocket.input.AutomationAction
import com.bettergi.pocket.input.AutomationController
import com.bettergi.pocket.input.BackAction
import com.bettergi.pocket.input.ClickAction

/**
 * root 输入后端：把自动化动作通过 [RootBridge] 注入为真实触摸事件。
 * 无无障碍依赖；注入失败仅记录日志，由上层状态机用画面变化自行验证。
 */
class RootAutomationController(
    private val bridge: RootBridge = RootBridge,
) : AutomationController {

    override fun execute(action: AutomationAction) {
        when (action) {
            is ClickAction -> bridge.tap(action.x, action.y, action.durationMs)
            BackAction -> bridge.back()
        }
    }
}