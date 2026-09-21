package com.zenlesszonezero.pocket.feature.autoskip

import android.util.Log
import com.zenlesszonezero.pocket.input.ActionEmitter
import com.zenlesszonezero.pocket.input.ClickAction
import com.zenlesszonezero.pocket.overlay.OverlayWindowController
import com.zenlesszonezero.pocket.recognition.CaptureContent
import com.zenlesszonezero.pocket.recognition.IntRect
import com.zenlesszonezero.pocket.recognition.RecognitionAssets
import com.zenlesszonezero.pocket.recognition.RecognitionObject
import com.zenlesszonezero.pocket.recognition.area.Region
import com.zenlesszonezero.pocket.recognition.opencv.MatOps
import com.zenlesszonezero.pocket.settings.TriggerSettings
import com.zenlesszonezero.pocket.trigger.FeatureTick
import com.zenlesszonezero.pocket.trigger.TriggerFeature
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * 绝区零自动对话（基于 BetterGI Pocket 框架改造）。
 *
 * 设计原则：**保守识别、选项优先、避免误点**。
 * - 识别区域按横/竖屏自适应（[zones]）且刻意收窄，只覆盖按钮/选项/对话框的可能位置。
 * - **悬浮窗面板展开时整体暂停**（[OverlayWindowController.panelExpanded]），避免把面板文字当选项点击。
 * - 「菜单」只作为**跳过**手段，不参与对话判定，避免 HUD 误判。
 *
 * 行为优先级：弹窗「确定」 > 选项点击 > 无选项时（快速跳过 / 继续）。
 */
class AutoSkipFeature(
    @Suppress("UNUSED_PARAMETER") private val assets: RecognitionAssets? = null,
    private val events: AutoSkipEvents? = null,
    private val optionKeywords: OptionKeywords = OptionKeywords(),
) : TriggerFeature {

    override val key: String = "AutoSkip"

    private enum class State { IDLE, IN_DIALOG, CONFIRMING }

    @Volatile private var state = State.IDLE
    @Volatile private var clickedOptionY: Int = -1
    @Volatile private var clickedAtMs: Long = 0L
    @Volatile private var lastOptionDecisionAtMs: Long = 0L
    @Volatile private var lastBlackClickMs: Long = 0L
    @Volatile private var lastIdleLogMs: Long = 0L
    @Volatile private var lastDialogueCheckMs: Long = 0L
    @Volatile private var lastDialogueResult: Boolean = false
    @Volatile private var lastConfirmCheckMs: Long = 0L
    @Volatile private var lastSkipClickMs: Long = 0L
    @Volatile private var lastContinueMs: Long = 0L
    @Volatile private var skipStreak: Int = 0

    override fun isEnabled(settings: TriggerSettings): Boolean =
        settings.screenShareEnabled && settings.autoSkipEnabled

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        // 悬浮窗面板展开时暂停识别与点击：面板文字不能被当作选项点击
        if (OverlayWindowController.panelExpanded) return
        val content = tick.content ?: return
        val cw = content.captureRectArea.width
        val ch = content.captureRectArea.height
        if (cw <= 0 || ch <= 0) return

        when (state) {
            State.IDLE -> onIdle(tick, content, cw, ch, settings, actions)
            State.IN_DIALOG -> onInDialog(tick, content, cw, ch, settings, actions)
            State.CONFIRMING -> onConfirming(content, cw, ch, actions)
        }
    }

    private fun onIdle(
        tick: FeatureTick,
        content: CaptureContent,
        cw: Int,
        ch: Int,
        settings: TriggerSettings,
        actions: ActionEmitter,
    ) {
        if (isDialogueScene(content)) {
            events?.onDialogueMatched()
            state = State.IN_DIALOG
            skipStreak = 0
            return
        }
        if (settings.blackScreenClickEnabled) clickBlackScreenIfNeeded(content, actions)
        val now = System.currentTimeMillis()
        if (now - lastIdleLogMs >= IDLE_LOG_INTERVAL_MS) {
            lastIdleLogMs = now
            events?.onIdleScan()
        }
    }

    private fun onInDialog(
        tick: FeatureTick,
        content: CaptureContent,
        cw: Int,
        ch: Int,
        settings: TriggerSettings,
        actions: ActionEmitter,
    ) {
        if (!isDialogueScene(content)) {
            state = State.IDLE
            skipStreak = 0
            return
        }
        events?.onDialogueMatched()
        val z = zones(cw, ch)

        // 1) 弹窗确认优先
        val ok = findConfirm(content, cw, ch)
        if (ok != null) {
            Log.i(TAG, "click confirm at ${ok.first},${ok.second}")
            events?.onAutoSkipLog("点击确定 (${ok.first}, ${ok.second})")
            actions.emit(ClickAction(ok.first, ok.second))
            return
        }

        val now = System.currentTimeMillis()
        if (now < clickedAtMs + CONFIRM_WINDOW_MS && clickedOptionY >= 0) return

        // 2) 选项优先：识别到选项即点击（自动对话主功能）
        if (now - lastOptionDecisionAtMs >= OPTION_DECISION_INTERVAL_MS) {
            lastOptionDecisionAtMs = now
            val decision = decideOption(content, cw, ch)
            if (decision != null) {
                val (tx, ty) = decision.centerOnNativeCapture()
                events?.onChatIconsRecognized(1, tx, ty)
                Log.i(TAG, "click option at $tx,$ty")
                events?.onAutoSkipLog("点击选项 ($tx, $ty)")
                events?.onChatIconClicked(tx, ty)
                actions.emit(ClickAction(tx, ty))
                clickedOptionY = decision.y
                clickedAtMs = now
                state = State.CONFIRMING
                return
            }
        }

        // 3) 无选项：快速跳过（跳过按钮，或只有菜单时长按菜单），否则点继续
        if (settings.quickSkipDialogueEnabled) {
            val skip = findButton(content, z, "跳过")
            if (skip != null) {
                if (now - lastSkipClickMs >= SKIP_CLICK_INTERVAL_MS) {
                    emitSkip(skip, skipStreak >= LONG_PRESS_AFTER, actions)
                    lastSkipClickMs = now
                    skipStreak++
                }
                return
            }
            val menu = findButton(content, z, "菜单")
            if (menu != null) {
                if (now - lastSkipClickMs >= SKIP_CLICK_INTERVAL_MS) {
                    emitSkip(menu, longPress = true, actions)
                    lastSkipClickMs = now
                    skipStreak++
                }
                return
            }
        }
        maybeContinue(tick, cw, ch, actions)
    }

    private fun emitSkip(target: Pair<Int, Int>, longPress: Boolean, actions: ActionEmitter) {
        val (x, y) = target
        val action = if (longPress) ClickAction(x, y, LONG_PRESS_MS) else ClickAction(x, y, TAP_MS)
        Log.i(TAG, "click skip longPress=$longPress at $x,$y")
        events?.onAutoSkipLog("点击跳过${if (longPress) "（长按）" else ""} ($x, $y)")
        actions.emit(action)
    }

    private fun onConfirming(
        content: CaptureContent,
        cw: Int,
        ch: Int,
        actions: ActionEmitter,
    ) {
        if (!isDialogueScene(content)) {
            state = State.IDLE
            clickedOptionY = -1
            return
        }
        val z = zones(cw, ch)
        val now = System.currentTimeMillis()
        val hits = ocr(content, z.option.x(cw), z.option.y(ch), z.option.w(cw), z.option.h(ch))
            .filter { !it.text.isNullOrBlank() }
        val top = hits.minByOrNull { it.y }
        val changed = top == null || top.y != clickedOptionY || hits.none { it.y == clickedOptionY }
        if (changed) {
            state = State.IN_DIALOG
            clickedOptionY = -1
            return
        }
        if (now - clickedAtMs >= CONFIRM_TIMEOUT_MS) {
            Log.w(TAG, "option did not change within ${CONFIRM_TIMEOUT_MS}ms, allow retry")
            state = State.IN_DIALOG
            clickedAtMs = 0L
        }
    }

    /** 对话中无选项时点击对话框推进，按原生比例换算（横竖屏自适应）。 */
    private fun maybeContinue(tick: FeatureTick, cw: Int, ch: Int, actions: ActionEmitter) {
        val now = System.currentTimeMillis()
        if (now - lastContinueMs < CONTINUE_INTERVAL_MS) return
        lastContinueMs = now
        val z = zones(cw, ch)
        val cx = (tick.screenWidth * z.continueX).toInt()
        val cy = (tick.screenHeight * z.continueY).toInt()
        Log.i(TAG, "click continue at $cx,$cy")
        events?.onAutoSkipLog("点击继续 ($cx, $cy)")
        actions.emit(ClickAction(cx, cy))
    }

    // ---------------- 识别 ----------------

    /**
     * 对话判定（350ms 节流）：右上角「跳过/自动/回顾」按钮命中，**或**底部对话框有文字，
     * **或** 选项区域存在有效选项。
     *
     * 注意：「菜单」不参与判定（过于常见，易把非对话界面/HUD 判成对话）。
     */
    private fun isDialogueScene(content: CaptureContent): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDialogueCheckMs < DIALOGUE_CHECK_INTERVAL_MS) return lastDialogueResult
        lastDialogueCheckMs = now

        val cw = content.captureRectArea.width
        val ch = content.captureRectArea.height
        val z = zones(cw, ch)

        val top = ocr(content, z.top.x(cw), z.top.y(ch), z.top.w(cw), z.top.h(ch))
        if (top.any { hasDialogueButton(it.text) }) {
            lastDialogueResult = true
            return true
        }

        val bottom = ocr(content, z.bottom.x(cw), z.bottom.y(ch), z.bottom.w(cw), z.bottom.h(ch))
        if (bottom.any { !it.text.isNullOrBlank() }) {
            lastDialogueResult = true
            return true
        }

        val opt = ocr(content, z.option.x(cw), z.option.y(ch), z.option.w(cw), z.option.h(ch))
        val result = opt.count { isValidOption(it.text) } >= 1
        lastDialogueResult = result
        return result
    }

    /** 仅「跳过/自动/回顾」视为对话功能按钮；不含「菜单」。 */
    private fun hasDialogueButton(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("跳过") || text.contains("自动") || text.contains("回顾")
    }

    /** 有效选项文字：非空、含中文、长度 2..30（过滤背景噪声与长句 HUD）。 */
    private fun isValidOption(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (t.length < 2 || t.length > 30) return false
        return t.any { it in '\u4e00'..'\u9fff' }
    }

    private fun findButton(content: CaptureContent, z: Zones, keyword: String): Pair<Int, Int>? {
        val cw = content.captureRectArea.width
        val ch = content.captureRectArea.height
        val top = ocr(content, z.top.x(cw), z.top.y(ch), z.top.w(cw), z.top.h(ch))
        val hit = top.firstOrNull { it.text?.contains(keyword) == true } ?: return null
        return hit.centerOnNativeCapture()
    }

    private fun findConfirm(content: CaptureContent, cw: Int, ch: Int): Pair<Int, Int>? {
        val now = System.currentTimeMillis()
        if (now - lastConfirmCheckMs < CONFIRM_CHECK_INTERVAL_MS) return null
        lastConfirmCheckMs = now
        val z = zones(cw, ch)
        val regions = ocr(content, z.confirm.x(cw), z.confirm.y(ch), z.confirm.w(cw), z.confirm.h(ch))
        val hit = regions.firstOrNull { it.text?.contains("确定") == true } ?: return null
        return hit.centerOnNativeCapture()
    }

    private fun decideOption(content: CaptureContent, cw: Int, ch: Int): Region? {
        val z = zones(cw, ch)
        val regions = ocr(content, z.option.x(cw), z.option.y(ch), z.option.w(cw), z.option.h(ch))
        val texts = regions.filter { isValidOption(it.text) }
        if (texts.isEmpty()) return null
        val sorted = texts.sortedBy { it.y }
        events?.onOptionTextsRecognized(sorted.mapNotNull { it.text })

        for (item in sorted) {
            val t = item.text ?: continue
            if (optionKeywords.select.any { t.contains(it) }) {
                events?.onAutoSkipLog("主动选择命中：$t")
                return item
            }
            if (optionKeywords.pause.any { t.contains(it) }) {
                events?.onAutoSkipLog("命中暂停词，等待手动选择：$t")
                events?.onPauseBlocked(t)
                return null
            }
        }
        for (item in sorted) {
            val t = item.text ?: continue
            if (optionKeywords.defaultPause.any { t.contains(it) }) {
                events?.onAutoSkipLog("命中默认暂停词，等待手动选择：$t")
                events?.onPauseBlocked(t)
                return null
            }
        }
        events?.onAutoSkipLog("无关键词命中，点最低选项")
        return sorted.last()
    }

    private fun ocr(content: CaptureContent, x: Int, y: Int, w: Int, h: Int): List<Region> {
        if (w <= 0 || h <= 0) return emptyList()
        val ro = RecognitionObject.ocrThis()
        ro.regionOfInterest = IntRect(x, y, w, h)
        return content.findMulti(ro)
    }

    private fun clickBlackScreenIfNeeded(content: CaptureContent, actions: ActionEmitter) {
        val now = System.currentTimeMillis()
        if (now - lastBlackClickMs < BLACK_CLICK_INTERVAL_MS) return
        val region = content.captureRectArea
        val grey = region.cacheGreyMatSafe ?: return
        val w = grey.cols()
        val h = grey.rows()
        if (w <= 0 || h < 30) return
        val top = h / 3
        val roi = MatOps.roiView(grey, IntRect(0, top, w, h - top * 2))
        val mask = Mat()
        try {
            Core.inRange(roi, Scalar(0.0), Scalar(0.0), mask)
            val black = Core.countNonZero(mask).toDouble()
            val rate = black / (roi.cols() * roi.rows())
            if (rate >= BLACK_RATE_MIN && rate < BLACK_RATE_MAX) {
                Log.i(TAG, "black transition detected, rate=$rate, click center")
                events?.onBlackScreenClicked(w / 2, h / 2)
                actions.emit(ClickAction(w / 2, h / 2))
                lastBlackClickMs = now
            }
        } finally {
            roi.release()
            mask.release()
        }
    }

    private data class Box(val fx: Double, val fy: Double, val fw: Double, val fh: Double) {
        fun x(cw: Int) = (cw * fx).toInt().coerceAtLeast(0)
        fun y(ch: Int) = (ch * fy).toInt().coerceAtLeast(0)
        fun w(cw: Int) = (cw * fw).toInt().coerceAtLeast(1)
        fun h(ch: Int) = (ch * fh).toInt().coerceAtLeast(1)
    }

    private data class Zones(
        val top: Box,
        val option: Box,
        val bottom: Box,
        val confirm: Box,
        val continueX: Double,
        val continueY: Double,
    )

    /** 按宽高比给出一组**收窄**的识别区域，避免误判与误点。 */
    private fun zones(cw: Int, ch: Int): Zones =
        if (cw > ch) {
            // 横屏（平板 / PC / 模拟器）
            Zones(
                top = Box(0.63, 0.00, 0.36, 0.15),
                option = Box(0.32, 0.30, 0.58, 0.42),
                bottom = Box(0.10, 0.68, 0.80, 0.22),
                confirm = Box(0.30, 0.38, 0.40, 0.22),
                continueX = 0.50,
                continueY = 0.72,
            )
        } else {
            // 竖屏（手机）
            Zones(
                top = Box(0.60, 0.00, 0.39, 0.12),
                option = Box(0.46, 0.26, 0.50, 0.48),
                bottom = Box(0.08, 0.76, 0.84, 0.16),
                confirm = Box(0.24, 0.44, 0.52, 0.20),
                continueX = 0.50,
                continueY = 0.82,
            )
        }

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "ZZZ.AutoSkip"

        private const val CONFIRM_WINDOW_MS = 600L
        private const val CONFIRM_TIMEOUT_MS = 1200L
        private const val OPTION_DECISION_INTERVAL_MS = 1000L
        private const val DIALOGUE_CHECK_INTERVAL_MS = 350L
        private const val CONFIRM_CHECK_INTERVAL_MS = 300L
        private const val SKIP_CLICK_INTERVAL_MS = 700L
        private const val CONTINUE_INTERVAL_MS = 600L
        private const val LONG_PRESS_AFTER = 2
        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 600L
        private const val BLACK_CLICK_INTERVAL_MS = 1200L
        private const val BLACK_RATE_MIN = 0.5
        private const val BLACK_RATE_MAX = 0.98999
        private const val IDLE_LOG_INTERVAL_MS = 5000L

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
