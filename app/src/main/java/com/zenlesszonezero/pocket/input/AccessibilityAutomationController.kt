package com.zenlesszonezero.pocket.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.zenlesszonezero.pocket.overlay.OverlayWindowController

class AccessibilityAutomationController(
    private val overlayController: OverlayWindowController,
) : AutomationController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restorePassthrough = Runnable { overlayController.restoreClickPassthrough() }

    @Volatile
    private var lastDiagMs: Long = 0L

    override fun execute(action: AutomationAction) {
        when (action) {
            is ClickAction -> executeClick(action)
            BackAction -> executeBack()
        }
    }

    private fun executeClick(action: ClickAction) {
        if (!InputAccessibilityService.isConnected()) {
            diag("无障碍服务未连接，点击已取消")
            return
        }

        mainHandler.post {
            val needPassthrough = overlayController.prepareClickPassthrough(action.x, action.y)
            val dispatched = InputAccessibilityService.click(action.x, action.y, action.durationMs)
            if (!dispatched) {
                if (needPassthrough) {
                    overlayController.restoreClickPassthrough()
                }
                diag("手势派发失败 @${action.x},${action.y}")
                return@post
            }
            overlayController.flashTap(action.x, action.y)
            if (needPassthrough) {
                mainHandler.removeCallbacks(restorePassthrough)
                mainHandler.postDelayed(restorePassthrough, action.durationMs + RESTORE_TOUCH_DELAY_MS)
            }
        }
    }

    private fun executeBack() {
        if (!InputAccessibilityService.isConnected()) {
            diag("无障碍服务未连接，返回已取消")
            return
        }
        mainHandler.post {
            if (!InputAccessibilityService.back()) {
                diag("返回动作失败")
            }
        }
    }

    /** 点击链路异常时写日志并（节流）弹 Toast，避免「识别到但无动作」无从排查。 */
    private fun diag(message: String) {
        Log.w(TAG, message)
        val now = System.currentTimeMillis()
        if (now - lastDiagMs < DIAG_INTERVAL_MS) return
        lastDiagMs = now
        mainHandler.post {
            InputAccessibilityService.appContextOrNull()?.let {
                Toast.makeText(it, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TAG = "BetterGI.Input"
        const val RESTORE_TOUCH_DELAY_MS = 40L
        const val DIAG_INTERVAL_MS = 3000L
    }
}
