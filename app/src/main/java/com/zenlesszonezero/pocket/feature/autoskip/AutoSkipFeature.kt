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
 * 识别层为「1080p 归一化比例区域 + ML Kit 中文 OCR」，不依赖模板图片。
 * **识别区域按横/竖屏自适应**（见 [zones]）：绝区零在手机为竖屏、平板/PC/模拟器为横屏，
 * 两者按钮与选项位置差别很大（横屏选项在屏幕正中央）。
 *
 * 行为：
 * 1. 对话判定：右上角功能按钮（跳过/自动/回顾/菜单）命中，**或选项区域有文字**，或底部对话气泡有文字。
 * 2. 快速跳过（若开启，优先）：有「跳过」按钮则点击（连续短按无效转长按）；**无「跳过」但只有「菜单」时长按「菜单」跳过**。
 * 3. 选项：OCR 选项文字 → 关键词决策（select > pause > defaultPause）→ 点最下方。
 * 4. 继续：对话中无选项时点击对话框推进。
 * 5. 弹窗确认：检测到中央「确定」即点击（覆盖长按跳过确认弹窗）。
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
        val z = zones(cw, ch)
        if (!isDialogueScene(content)) {
            state = State.IDLE
            skipStreak = 0
            return
        }
        events?.onDialogueMatched()

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

        // 2) 快速跳过（若开启，优先于选项）
        if (settings.quickSkipDialogueEnabled) {
            val skip = findButton(content, z, "跳过")
            if (skip != null) {
                if (now - lastSkipClickMs >= SKIP_CLICK_INTERVAL_MS) {
                    val longPress = skipStreak >= LONG_PRESS_AFTER
                    emitSkip(skip, longPress, actions)
                    lastSkipClickMs = now
                    skipStreak++
                }
                return
            }
            // 无「跳过」按钮时：长按「菜单」跳过（绝区零部分对话仅提供菜单）
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

        // 3) 选项优先：识别到选项即点击
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

        // 4) 无选项：点继续推进
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
     * 对话判定（350ms 节流缓存）：右上功能按钮命中 **或** 选项区域有文字 **或** 底部对话框有文字。
     * 「选项区域有文字」是关键——部分对话界面（如中央双选项）没有底部气泡、也没有跳过/自动/回顾按钮。
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

        val opt = ocr(content, z.option.x(cw), z.option.y(ch), z.option.w(cw), z.option.h(ch))
        if (opt.any { isValidOption(it.text) }) {
            lastDialogueResult = true
            return true
        }

        val bottom = ocr(content, z.bottom.x(cw), z.bottom.y(ch), z.bottom.w(cw), z.bottom.h(ch))
        val result = bottom.any { !it.text.isNullOrBlank() }
        lastDialogueResult = result
        return result
    }

    private fun hasDialogueButton(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("跳过") || text.contains("自动") ||
            text.contains("回顾") || text.contains("菜单")
    }

    /** 有效选项文字：非空、长度 >= 2、且含中文（过滤背景噪声/英文碎片）。 */
    private fun isValidOption(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length < 2) return false
        return text.any { it in '\u4e00'..'\u9fff' }
    }

    /** 在右上功能按钮区寻找包含 [keyword] 的按钮，返回屏幕原生中心坐标。 */
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

    /**
     * 选项决策（对齐 PC 版 ChatOptionChoose 的优先级）：
     * select 主动选择 > pause 暂停 > defaultPause 默认暂停 > 兜底点最下方。
     */
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

    /** 一套识别区域 + 继续点击比例。 */
    private data class Zones(
        val top: Box,
        val option: Box,
        val bottom: Box,
        val confirm: Box,
        val continueX: Double,
        val continueY: Double,
    )

    /** 按 captureRectArea 宽高判断横/竖屏，给出对应比例区域。 */
    private fun zones(cw: Int, ch: Int): Zones =
        if (cw > ch) {
            // 横屏（平板 / PC / 模拟器）：选项在屏幕正中，右上角为「跳过/自动/回顾/菜单」按钮组
            Zones(
                top = Box(0.62, 0.01, 0.37, 0.20),
                option = Box(0.14, 0.24, 0.72, 0.50),
                bottom = Box(0.04, 0.60, 0.92, 0.38),
                confirm = Box(0.28, 0.36, 0.44, 0.26),
                continueX = 0.50,
                continueY = 0.74,
            )
        } else {
            // 竖屏（手机）
            Zones(
                top = Box(0.58, 0.01, 0.40, 0.15),
                option = Box(0.40, 0.16, 0.58, 0.68),
                bottom = Box(0.06, 0.68, 0.88, 0.28),
                confirm = Box(0.22, 0.42, 0.56, 0.22),
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
