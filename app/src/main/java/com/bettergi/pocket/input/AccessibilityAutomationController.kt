package com.bettergi.pocket.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.bettergi.pocket.overlay.OverlayWindowController

class AccessibilityAutomationController(
    private val context: Context,
    private val overlayController: OverlayWindowController,
) : AutomationController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restorePassthrough = Runnable { overlayController.restoreClickPassthrough() }

    /** 用户手指是否按在屏幕上（触摸期间不注入点击，避免打断用户操作）。 */
    @Volatile
    private var userTouching = false

    private val touchReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            userTouching = intent?.getBooleanExtra(
                InputAccessibilityService.EXTRA_TOUCHING,
                false,
            ) ?: false
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            touchReceiver,
            IntentFilter(InputAccessibilityService.ACTION_TOUCH_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun execute(action: AutomationAction) {
        when (action) {
            is ClickAction -> executeClick(action)
            BackAction -> executeBack()
        }
    }

    private fun executeClick(action: ClickAction) {
        if (!InputAccessibilityService.isConnected()) {
            Log.w(TAG, "skip click, accessibility service is not connected")
            return
        }
        if (userTouching) {
            Log.i(TAG, "skip click while user is touching at ${action.x},${action.y}")
            return
        }
        mainHandler.post {
            if (userTouching) return@post
            val needPassthrough = overlayController.prepareClickPassthrough(action.x, action.y)
            val dispatched = InputAccessibilityService.click(action.x, action.y, action.durationMs)
            if (!dispatched) {
                if (needPassthrough) {
                    overlayController.restoreClickPassthrough()
                }
                Log.w(TAG, "dispatchGesture failed at ${action.x},${action.y}")
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
            Log.w(TAG, "skip back, accessibility service is not connected")
            return
        }
        mainHandler.post {
            if (!InputAccessibilityService.back()) {
                Log.w(TAG, "back action failed")
            }
        }
    }

    private companion object {
        const val TAG = "BetterGI.Input"
        const val RESTORE_TOUCH_DELAY_MS = 40L
    }
}
