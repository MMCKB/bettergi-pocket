package com.zenlesszonezero.pocket.feature.autopick

import com.zenlesszonezero.pocket.input.ActionEmitter
import com.zenlesszonezero.pocket.input.ClickAction
import com.zenlesszonezero.pocket.settings.TriggerSettings
import com.zenlesszonezero.pocket.trigger.FeatureTick
import com.zenlesszonezero.pocket.trigger.TriggerFeature
import com.zenlesszonezero.pocket.trigger.screenBottomCenter

class AutoPickFeature : TriggerFeature {
    override val key: String = "AutoPick"

    @Volatile
    private var nextClickAtMs: Long = 0L

    override fun isEnabled(settings: TriggerSettings): Boolean =
        AVAILABLE && settings.screenShareEnabled && settings.autoPickEnabled

    override fun needsFrame(settings: TriggerSettings): Boolean = false

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val now = System.currentTimeMillis()
        if (now < nextClickAtMs) return
        val (x, y) = screenBottomCenter(tick.screenWidth, tick.screenHeight)
        actions.emit(ClickAction(x, y))
        nextClickAtMs = now + CLICK_INTERVAL_MS
    }

    companion object {
        const val CLICK_INTERVAL_MS = 800L

        /** Temporarily disabled — set true to restore auto-pick UI and behavior. */
        const val AVAILABLE = false
    }
}
