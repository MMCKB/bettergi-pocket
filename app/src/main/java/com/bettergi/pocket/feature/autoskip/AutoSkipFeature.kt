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
import kotlin.math.abs
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
                if (inDialogue(content)) {
                    events?.onTalkHistoryMatched()
                    state = State.IN_DIALOG
                    return
                }
                if (settings.blackScreenClickEnabled) clickBlackScreenIfNeeded(content, actions)
            }

            State.IN_DIALOG -> {
                if (!inDialogue(content)) {
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
                if (excls.isNotEmpty()) {
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
                if (!inDialogue(content)) {
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
            Core.inRange(roi, Scalar(0.0), Scalar(BLACK_GRAY_MAX), mask)
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
    private fun inDialogue(content: CaptureContent): Boolean {
        if (isDialogueScene(content, assets)) return true
        val now = System.currentTimeMillis()
        if (now - lastOcrCheckMs < OCR_CHECK_INTERVAL_MS) return lastOcrDialogueHit
        lastOcrCheckMs = now
        val ro = assets.get(TASK_NAME, "DialogueText", content.captureRectArea)
        lastOcrDialogueHit = content.find(ro).isExist()
        return lastOcrDialogueHit
    }

    /** 关键词决策：select 优先，pause 过滤，兜底第一个可点选项。 */
    private fun decideOption(content: CaptureContent, hits: List<Region>): Region? {
        if (hits.isEmpty()) return null
        val region = content.captureRectArea
        val textLines = ocrOptionTexts(region, hits)
        val paired = hits.map { hit ->
            val line = textLines.minByOrNull { abs(it.y - hit.y) }
            val text = if (line != null && abs(line.y - hit.y) <= hit.height * 2) line.text else null
            hit to text
        }
        for ((hit, text) in paired) {
            if (text != null && optionKeywords.select.any { text.contains(it) }) return hit
        }
        val clickable = paired.filter { (_, text) ->
            text == null || optionKeywords.pause.none { text.contains(it) }
        }
        return clickable.firstOrNull()?.first ?: hits.firstOrNull()
    }

    /** 对选项气泡右侧文字区域做一次 OCR，返回文本行（识别区域坐标）。 */
    private fun ocrOptionTexts(
        region: com.bettergi.pocket.recognition.area.ImageRegion,
        hits: List<Region>,
    ): List<Region> {
        val minY = hits.minOf { it.y }
        val maxY = hits.maxOf { it.y + it.height }
        val left = (hits.minOf { it.x + it.width } + 8).coerceAtMost(region.width - 1)
        val width = region.width - left
        val height = (maxY - minY + 20).coerceAtMost(region.height - minY)
        if (width <= 0 || height <= 0) return emptyList()
        val ro = RecognitionObject.ocrThis()
        ro.regionOfInterest = IntRect(left, minY, width, height)
        return region.findMulti(ro)
    }

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "BetterGI.AutoSkip"
        private const val CONFIRM_WINDOW_MS = 600L
        private const val CONFIRM_TIMEOUT_MS = 1200L
        private const val OCR_CHECK_INTERVAL_MS = 1500L
        private const val OPTION_DECISION_INTERVAL_MS = 1000L
        private const val BLACK_CLICK_INTERVAL_MS = 1200L
        private const val BLACK_GRAY_MAX = 30.0
        private const val BLACK_RATE_MIN = 0.5
        private const val BLACK_RATE_MAX = 0.98999

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
