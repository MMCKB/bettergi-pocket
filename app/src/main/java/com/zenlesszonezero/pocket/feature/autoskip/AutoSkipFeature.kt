package com.zenlesszonezero.pocket.feature.autoskip

import android.util.Log
import com.zenlesszonezero.pocket.input.ActionEmitter
import com.zenlesszonezero.pocket.input.ClickAction
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
 * 识别层由「PNG 模板匹配」改为「1080p 归一化比例区域 + ML Kit 中文 OCR」，
 * 因此不再依赖 assets/recognition/AutoSkip 下的模板图片，只需校准坐标比例。
 *
 * 行为：
 * 1. 对话判定：OCR 命中右上角功能按钮（跳过/自动/回顾/菜单）或底部对话气泡有文字。
 * 2. 选项：右侧区域 OCR 读选项文字 → 关键词决策（select > pause > defaultPause）→ 点最下方。
 * 3. 继续：对话中无选项时，点击对话气泡推进（» 箭头所在区域）。
 * 4. 跳过：检测到「跳过」按钮时点击；连续短按无效则长按触发跳过（长按会弹出确认弹窗）。
 * 5. 弹窗确认：任何状态检测到中央「确定」按钮即点击（覆盖长按跳过等确认弹窗）。
 *
 * 坐标说明：OCR 区域基于 captureRectArea（1080p 归一化，竖屏宽 1080）。
 * 点击坐标：OCR 结果用 centerOnNativeCapture() 转屏幕原生；固定比例用 tick.screen*比例。
 * 比例系数需按真机截图校准（见 README「坐标校准」）。
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

        // 1) 弹窗确认优先：检测到中央「确定」即点击（处理长按跳过确认等）
        val ok = findConfirm(content, cw, ch)
        if (ok != null) {
            Log.i(TAG, "click confirm at ${ok.first},${ok.second}")
            events?.onAutoSkipLog("点击确定 (${ok.first}, ${ok.second})")
            actions.emit(ClickAction(ok.first, ok.second))
            return
        }

        // 2) 快速跳过：检测到「跳过」按钮时点击；连续无效则长按触发弹窗
        if (settings.quickSkipDialogueEnabled) {
            val skip = findSkipButton(content, cw, ch)
            if (skip != null) {
                val now = System.currentTimeMillis()
                if (now - lastSkipClickMs >= SKIP_CLICK_INTERVAL_MS) {
                    val longPress = skipStreak >= LONG_PRESS_AFTER
                    val (sx, sy) = skip
                    val action = if (longPress) ClickAction(sx, sy, LONG_PRESS_MS)
                    else ClickAction(sx, sy, TAP_MS)
                    Log.i(TAG, "click skip longPress=$longPress at $sx,$sy")
                    events?.onAutoSkipLog("点击跳过${if (longPress) "（长按）" else ""} ($sx, $sy)")
                    actions.emit(action)
                    lastSkipClickMs = now
                    skipStreak++
                }
                return
            }
        }

        // 3) 选项 / 继续
        val now = System.currentTimeMillis()
        if (now < clickedAtMs + CONFIRM_WINDOW_MS && clickedOptionY >= 0) return
        if (now - lastOptionDecisionAtMs < OPTION_DECISION_INTERVAL_MS) {
            maybeContinue(tick, actions)
            return
        }
        lastOptionDecisionAtMs = now
        val decision = decideOption(content, cw, ch)
        if (decision == null) {
            maybeContinue(tick, actions)
            return
        }
        val (tx, ty) = decision.centerOnNativeCapture()
        events?.onChatIconsRecognized(1, tx, ty)
        Log.i(TAG, "click option at $tx,$ty")
        events?.onAutoSkipLog("点击选项 ($tx, $ty)")
        events?.onChatIconClicked(tx, ty)
        clickedOptionY = decision.y
        clickedAtMs = now
        state = State.CONFIRMING
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
        val now = System.currentTimeMillis()
        val hits = ocr(content, OPTION.x(cw), OPTION.y(ch), OPTION.w(cw), OPTION.h(ch))
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

    /** 对话中无选项时点击对话气泡推进（» 箭头所在区域），按原生比例换算。 */
    private fun maybeContinue(tick: FeatureTick, actions: ActionEmitter) {
        val now = System.currentTimeMillis()
        if (now - lastContinueMs < CONTINUE_INTERVAL_MS) return
        lastContinueMs = now
        val cx = (tick.screenWidth * CONTINUE_X).toInt()
        val cy = (tick.screenHeight * CONTINUE_Y).toInt()
        Log.i(TAG, "click continue at $cx,$cy")
        events?.onAutoSkipLog("点击继续 ($cx, $cy)")
        actions.emit(ClickAction(cx, cy))
    }

    // ---------------- 识别 ----------------

    /** 对话判定：右上角功能按钮命中 或 底部对话气泡有文字。结果按 350ms 节流缓存。 */
    private fun isDialogueScene(content: CaptureContent): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDialogueCheckMs < DIALOGUE_CHECK_INTERVAL_MS) return lastDialogueResult
        lastDialogueCheckMs = now
        val cw = content.captureRectArea.width
        val ch = content.captureRectArea.height
        val top = ocr(content, TOP.x(cw), TOP.y(ch), TOP.w(cw), TOP.h(ch))
        if (top.any { hasDialogueButton(it.text) }) {
            lastDialogueResult = true
            return true
        }
        val bottom = ocr(content, BOTTOM.x(cw), BOTTOM.y(ch), BOTTOM.w(cw), BOTTOM.h(ch))
        val result = bottom.any { !it.text.isNullOrBlank() }
        lastDialogueResult = result
        return result
    }

    private fun hasDialogueButton(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("跳过") || text.contains("自动") ||
            text.contains("回顾") || text.contains("菜单")
    }

    private fun findSkipButton(content: CaptureContent, cw: Int, ch: Int): Pair<Int, Int>? {
        val top = ocr(content, TOP.x(cw), TOP.y(ch), TOP.w(cw), TOP.h(ch))
        val hit = top.firstOrNull { it.text?.contains("跳过") == true } ?: return null
        return hit.centerOnNativeCapture()
    }

    private fun findConfirm(content: CaptureContent, cw: Int, ch: Int): Pair<Int, Int>? {
        val now = System.currentTimeMillis()
        if (now - lastConfirmCheckMs < CONFIRM_CHECK_INTERVAL_MS) return null
        lastConfirmCheckMs = now
        val regions = ocr(content, CONFIRM.x(cw), CONFIRM.y(ch), CONFIRM.w(cw), CONFIRM.h(ch))
        val hit = regions.firstOrNull { it.text?.contains("确定") == true } ?: return null
        return hit.centerOnNativeCapture()
    }

    /**
     * 选项决策（对齐 PC 版 ChatOptionChoose 的优先级）：
     * select 主动选择 > pause 暂停 > defaultPause 默认暂停 > 兜底点最下方。
     */
    private fun decideOption(content: CaptureContent, cw: Int, ch: Int): Region? {
        val regions = ocr(content, OPTION.x(cw), OPTION.y(ch), OPTION.w(cw), OPTION.h(ch))
        val texts = regions.filter { val t = it.text; !t.isNullOrBlank() && t.length >= 2 }
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

    /** 在 captureRectArea（1080p 归一化）内做 OCR，返回带文字的识别块。 */
    private fun ocr(content: CaptureContent, x: Int, y: Int, w: Int, h: Int): List<Region> {
        if (w <= 0 || h <= 0) return emptyList()
        val ro = RecognitionObject.ocrThis()
        ro.regionOfInterest = IntRect(x, y, w, h)
        return content.findMulti(ro)
    }

    /** 黑屏转场点击（保留原版，基于 captureRectArea 灰度）。 */
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

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "ZZZ.AutoSkip"

        // 1080p 归一化识别区域（相对 captureRectArea 的比例，竖屏）
        private val TOP = Box(0.60, 0.01, 0.38, 0.12)     // 右上角功能按钮
        private val BOTTOM = Box(0.06, 0.70, 0.88, 0.24)  // 底部对话气泡
        private val OPTION = Box(0.42, 0.20, 0.56, 0.64)  // 右侧选项
        private val CONFIRM = Box(0.22, 0.44, 0.56, 0.20) // 中央「确定」弹窗

        // 点击坐标（屏幕原生比例）：对话气泡中部偏下，点击推进
        private const val CONTINUE_X = 0.50
        private const val CONTINUE_Y = 0.82

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
