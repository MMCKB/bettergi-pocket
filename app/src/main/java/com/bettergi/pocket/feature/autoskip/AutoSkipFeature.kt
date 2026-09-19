package com.bettergi.pocket.feature.autoskip

import android.util.Log
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.ClickAction
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.recognition.opencv.MatOps
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.trigger.FeatureTick
import com.bettergi.pocket.trigger.TriggerFeature
import com.bettergi.pocket.trigger.screenBottomCenter
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * 自动对话（移植 PC 版 AutoSkip 核心，去掉语音/弹窗/邀约）：
 * 感叹号优先、选项 OCR + 关键词决策、状态机 + 点击确认、黑屏转场点击。
 */
class AutoSkipFeature(
    private val assets: RecognitionAssets,
    private val events: AutoSkipEvents? = null,
    private val optionKeywords: OptionKeywords = OptionKeywords(),
) : TriggerFeature {

    override val key: String = "AutoSkip"

    private enum class State { IDLE, IN_DIALOG, CONFIRMING }

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var clickedOptionY: Int = -1

    @Volatile
    private var clickedAtMs: Long = 0L

    @Volatile
    private var lastOcrCheckMs: Long = 0L

    @Volatile
    private var lastOcrDialogueHit: Boolean = false

    @Volatile
    private var lastOptionDecisionAtMs: Long = 0L

    @Volatile
    private var lastBlackClickMs: Long = 0L

    override fun isEnabled(settings: TriggerSettings): Boolean =
        settings.screenShareEnabled && settings.autoSkipEnabled

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val content = tick.content ?: return

        when (state) {
            State.IDLE -> {
                if (inDialogue(content, settings)) {
                    events?.onTalkHistoryMatched()
                    state = State.IN_DIALOG
                    return
                }
                if (settings.blackScreenClickEnabled) clickBlackScreenIfNeeded(content, actions)
            }

            State.IN_DIALOG -> {
                if (!inDialogue(content, settings)) {
                    state = State.IDLE
                    return
                }
                events?.onTalkHistoryMatched()

                if (settings.quickSkipDialogueEnabled) {
                    val (skipX, skipY) = screenBottomCenter(tick.screenWidth, tick.screenHeight)
                    actions.emit(ClickAction(skipX, skipY))
                }

                val now = System.currentTimeMillis()
                if (now < clickedAtMs + CONFIRM_WINDOW_MS && clickedOptionY >= 0) return

                // 1) 感叹号选项：优先级最高，命中直接点
                val excls = content.findMulti(
                    assets.get(TASK_NAME, "ExclamationIcon", content.captureRectArea),
                )
                if (settings.smartOptionEnabled && excls.isNotEmpty()) {
                    val (x, y) = excls[0].centerOnNativeCapture()
                    Log.i(TAG, "click exclamation option at $x,$y")
                    actions.emit(ClickAction(x, y))
                    events?.onChatIconClicked(x, y)
                    clickedOptionY = excls[0].y
                    clickedAtMs = now
                    state = State.CONFIRMING
                    return
                }

                // 2) 普通选项：OCR 读文字 + 关键词决策（节流 1s）
                if (now - lastOptionDecisionAtMs < OPTION_DECISION_INTERVAL_MS) return
                lastOptionDecisionAtMs = now

                val chatIcon = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
                val hits = content.findMulti(chatIcon)
                val target = if (settings.smartOptionEnabled) {
                    decideOption(content, hits)
                } else {
                    selectTopChatIcon(hits)
                } ?: return
                val (topX, topY) = target.centerOnNativeCapture()
                events?.onChatIconsRecognized(hits.size, topX, topY)

                Log.i(TAG, "click option at $topX,$topY")
                actions.emit(ClickAction(topX, topY))
                events?.onChatIconClicked(topX, topY)
                clickedOptionY = target.y
                clickedAtMs = now
                state = State.CONFIRMING
            }

            State.CONFIRMING -> {
                if (!inDialogue(content, settings)) {
                    state = State.IDLE
                    return
                }
                val now = System.currentTimeMillis()
                val chatIcon = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
                val hits = content.findMulti(chatIcon)
                val top = selectTopChatIcon(hits)

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
        }
    }

    /** 黑屏转场检测：非对话时画面中部 1/3 区域接近全黑则点击推进（移植 PC 版）。 */
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
                actions.emit(ClickAction(w / 2, h / 2))
                lastBlackClickMs = now
            }
        } finally {
            roi.release()
            mask.release()
        }
    }

    /** 模板快速判定 + OCR 低频判定，任一命中即视为剧情对话中。 */
    private fun inDialogue(content: CaptureContent, settings: TriggerSettings): Boolean {
        if (isDialogueScene(content, assets)) return true
        if (!settings.smartOptionEnabled) return false
        val now = System.currentTimeMillis()
        if (now - lastOcrCheckMs < OCR_CHECK_INTERVAL_MS) return lastOcrDialogueHit
        lastOcrCheckMs = now
        val ro = assets.get(TASK_NAME, "DialogueText", content.captureRectArea)
        lastOcrDialogueHit = content.find(ro).isExist()
        return lastOcrDialogueHit
    }

    /**
     * 关键词决策（对齐 PC 版 ChatOptionChoose）：
     * 焦点选项在最低（Y 最大）；OCR 固定宽度区域读文字；
     * 先查 pause（命中即停不点），再查 select（命中点文字行），兜底点最低。
     */
    private fun decideOption(content: CaptureContent, hits: List<Region>): Region? {
        if (hits.isEmpty()) return null
        val region = content.captureRectArea
        val lowest = hits.maxByOrNull { it.y } ?: return null

        val scale = region.width / 1920.0
        val ocrLeft = (lowest.x + lowest.width + 8 * scale).toInt().coerceAtMost(region.width - 1)
        val ocrWidth = (535 * scale).toInt().coerceAtLeast(80)
        val ocrTop = (region.height / 12).coerceAtLeast(0)
        val ocrBottom = (lowest.y + lowest.height + 30 * scale).toInt().coerceAtMost(region.height)
        val ocrHeight = ocrBottom - ocrTop
        if (ocrLeft < 0 || ocrWidth <= 0 || ocrHeight <= 0) return lowest

        val ro = RecognitionObject.ocrThis()
        ro.regionOfInterest = IntRect(ocrLeft, ocrTop, ocrWidth, ocrHeight)
        val lines = region.findMulti(ro)

        val rs = lines
            .filter { line ->
                val t = line.text ?: return@filter false
                if (t.isBlank()) return@filter false
                if (t.length < 5 && t.all { it in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ." }) {
                    return@filter false
                }
                true
            }
            .sortedBy { it.y }
        if (rs.isEmpty()) return lowest

        for (item in rs) {
            val t = item.text ?: continue
            if (optionKeywords.pause.any { t.contains(it) }) return null
        }
        for (item in rs) {
            val t = item.text ?: continue
            if (optionKeywords.select.any { t.contains(it) }) return item
        }
        return rs.last()
    }

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "BetterGI.AutoSkip"
        private const val CONFIRM_WINDOW_MS = 600L
        private const val CONFIRM_TIMEOUT_MS = 1200L
        private const val OCR_CHECK_INTERVAL_MS = 1500L
        private const val OPTION_DECISION_INTERVAL_MS = 1000L
        private const val BLACK_CLICK_INTERVAL_MS = 1200L
        private const val BLACK_RATE_MIN = 0.5
        private const val BLACK_RATE_MAX = 0.98999

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
