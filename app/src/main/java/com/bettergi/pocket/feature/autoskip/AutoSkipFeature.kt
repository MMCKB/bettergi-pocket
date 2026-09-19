package com.bettergi.pocket.feature.autoskip

import android.util.Log
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.ClickAction
import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.area.ImageRegion
import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.recognition.opencv.MatOps
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.trigger.FeatureTick
import com.bettergi.pocket.trigger.TriggerFeature
import com.bettergi.pocket.trigger.screenBottomCenter
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 自动对话（移植 PC 版 AutoSkip 核心，去掉语音/弹窗/邀约）：
 * 感叹号优先、选项 OCR + 关键词决策、橙色关键选项、
 * 状态机 + 点击确认、黑屏转场点击。
 */
class AutoSkipFeature(
    private val assets: RecognitionAssets,
    private val events: AutoSkipEvents? = null,
    private val optionKeywords: OptionKeywords = OptionKeywords(),
    private val isGenshinForeground: () -> Boolean = { true },
) : TriggerFeature {

    override val key: String = "AutoSkip"

    private enum class State { IDLE, IN_DIALOG, CONFIRMING }

    private data class Decision(val region: Region)

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
    private var dialogueMissStreak: Int = 0

    @Volatile
    private var lastChatIconCheckMs: Long = 0L

    @Volatile
    private var lastChatIconHit: Boolean = false

    @Volatile
    private var lastOptionDecisionAtMs: Long = 0L

    @Volatile
    private var lastBlackClickMs: Long = 0L

    @Volatile
    private var lastIdleLogMs: Long = 0L

    @Volatile
    private var lastForegroundCheckMs: Long = 0L

    @Volatile
    private var lastForegroundResult: Boolean = true

    @Volatile
    private var lastSkipLogMs: Long = 0L

    override fun isEnabled(settings: TriggerSettings): Boolean =
        settings.screenShareEnabled && settings.autoSkipEnabled

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val content = tick.content ?: return
        if (settings.genshinForegroundOnly && !foregroundOk()) return

        when (state) {
            State.IDLE -> {
                if (inDialogue(content, settings)) {
                    events?.onTalkHistoryMatched()
                    state = State.IN_DIALOG
                    return
                }
                if (settings.blackScreenClickEnabled) clickBlackScreenIfNeeded(content, actions)
                val idleNow = System.currentTimeMillis()
                if (idleNow - lastIdleLogMs >= IDLE_LOG_INTERVAL_MS) {
                    lastIdleLogMs = idleNow
                    events?.onIdleScan()
                }
            }

            State.IN_DIALOG -> {
                if (!inDialogue(content, settings)) {
                    state = State.IDLE
                    return
                }
                events?.onTalkHistoryMatched()

                if (settings.quickSkipDialogueEnabled) {
                    val (skipX, skipY) = screenBottomCenter(tick.screenWidth, tick.screenHeight)
                    val skipNow = System.currentTimeMillis()
                    if (skipNow - lastSkipLogMs >= SKIP_LOG_INTERVAL_MS) {
                        lastSkipLogMs = skipNow
                        events?.onAutoSkipLog("点击跳过 ($skipX, $skipY)")
                    }
                    actions.emit(ClickAction(skipX, skipY))
                }

                val now = System.currentTimeMillis()
                if (now < clickedAtMs + CONFIRM_WINDOW_MS && clickedOptionY >= 0) return

                // 1) 感叹号选项：优先级最高，命中直接点
                val excls = content.findMulti(
                    assets.get(TASK_NAME, "ExclamationIcon", content.captureRectArea),
                )
                if (settings.smartOptionEnabled && settings.exclamationClickEnabled && excls.isNotEmpty()) {
                    val (x, y) = excls[0].centerOnNativeCapture()
                    Log.i(TAG, "click exclamation option at $x,$y")
                    events?.onAutoSkipLog("点击感叹号选项 ($x, $y)")
                    actions.emit(ClickAction(x, y))
                    events?.onChatIconClicked(x, y)
                    clickedOptionY = excls[0].y
                    clickedAtMs = now
                    state = State.CONFIRMING
                    return
                }

                // 2) 普通选项：OCR 读文字 + 关键词/橙色决策（节流 1s）
                if (now - lastOptionDecisionAtMs < OPTION_DECISION_INTERVAL_MS) return
                lastOptionDecisionAtMs = now

                val chatIcon = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
                val hits = content.findMulti(chatIcon)
                val decision = if (settings.smartOptionEnabled) {
                    decideOption(content, hits, settings)
                } else {
                    selectTopChatIcon(hits)?.let { Decision(it) }
                } ?: return
                val target = decision.region
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
                events?.onBlackScreenClicked(w / 2, h / 2)
                actions.emit(ClickAction(w / 2, h / 2))
                lastBlackClickMs = now
            }
        } finally {
            roi.release()
            mask.release()
        }
    }

    /** 原神是否在前台（节流查询，避免每拍跨进程调用）。 */
    private fun foregroundOk(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheckMs < FOREGROUND_CHECK_INTERVAL_MS) return lastForegroundResult
        lastForegroundCheckMs = now
        lastForegroundResult = isGenshinForeground()
        return lastForegroundResult
    }

    /**
     * 对话判定：
     * 强信号（历史图标 / 选项气泡）每拍判定；弱信号（OCR 对话文本，带长度行数过滤）仅用于进入；
     * 连续 MISS 若干拍才判为退出，避免迟滞与闪断。
     */
    private fun inDialogue(content: CaptureContent, settings: TriggerSettings): Boolean {
        if (isDialogueScene(content, assets) || hasChatIcons(content)) {
            dialogueMissStreak = 0
            return true
        }
        if (settings.smartOptionEnabled && ocrDialogueHit(content)) {
            dialogueMissStreak = 0
            return true
        }
        dialogueMissStreak++
        return dialogueMissStreak < DIALOGUE_EXIT_CONFIRM_COUNT
    }

    /** 选项气泡存在属于强对话信号（500ms 缓存）。 */
    private fun hasChatIcons(content: CaptureContent): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastChatIconCheckMs < CHAT_ICON_CHECK_INTERVAL_MS) return lastChatIconHit
        lastChatIconCheckMs = now
        val ro = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
        lastChatIconHit = content.findMulti(ro).isNotEmpty()
        return lastChatIconHit
    }

    /** OCR 对话文本判定：需满足最小长度与行数限制，1.5s 节流。 */
    private fun ocrDialogueHit(content: CaptureContent): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastOcrCheckMs < OCR_CHECK_INTERVAL_MS) return lastOcrDialogueHit
        lastOcrCheckMs = now
        val ro = assets.get(TASK_NAME, "DialogueText", content.captureRectArea)
        val lines = content.findMulti(ro)
        val text = lines.joinToString("") { it.text ?: "" }
            .replace(" ", "")
            .replace("\n", "")
        lastOcrDialogueHit = text.length >= DIALOGUE_TEXT_MIN_LEN &&
            lines.size <= DIALOGUE_TEXT_MAX_LINES
        return lastOcrDialogueHit
    }

    /** 橙色文字判定（对齐 PC IsOrangeOption）：RGB 243-255/195-205/48-55 占比 > 6%。 */
    private fun isOrangeOption(region: ImageRegion, item: Region): Boolean {
        val rect = item.toRect()
        if (rect.width <= 0 || rect.height <= 0) return false
        val roi = MatOps.roiView(region.srcMat, rect)
        val rgb = Mat()
        val mask = Mat()
        return try {
            Imgproc.cvtColor(roi, rgb, Imgproc.COLOR_BGR2RGB)
            Core.inRange(rgb, Scalar(243.0, 195.0, 48.0), Scalar(255.0, 205.0, 55.0), mask)
            val rate = Core.countNonZero(mask).toDouble() / (roi.cols() * roi.rows())
            rate > ORANGE_RATE_MIN
        } catch (e: Exception) {
            false
        } finally {
            roi.release()
            rgb.release()
            mask.release()
        }
    }

    /**
     * 关键词决策（对齐 PC 版 ChatOptionChoose）：
     * 焦点选项在最低（Y 最大）；OCR 固定宽度区域读文字；
     * pause 过滤 → select 命中点文字行 → 橙色关键选项 → 兜底点最低。
     */
    private fun decideOption(
        content: CaptureContent,
        hits: List<Region>,
        settings: TriggerSettings,
    ): Decision? {
        if (hits.isEmpty()) return null
        val region = content.captureRectArea
        val lowest = hits.maxByOrNull { it.y } ?: return null

        val scale = region.width / 1920.0
        val ocrLeft = (lowest.x + lowest.width + 8 * scale).toInt().coerceAtMost(region.width - 1)
        val ocrWidth = (535 * scale).toInt().coerceAtLeast(80)
        val ocrTop = (region.height / 12).coerceAtLeast(0)
        val ocrBottom = (lowest.y + lowest.height + 30 * scale).toInt().coerceAtMost(region.height)
        val ocrHeight = ocrBottom - ocrTop
        if (ocrLeft < 0 || ocrWidth <= 0 || ocrHeight <= 0) return Decision(lowest)

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
        if (rs.isEmpty()) return Decision(lowest)

        events?.onOptionTextsRecognized(rs.mapNotNull { it.text })

        // pause 词只过滤对应选项，不整体停；全部被过滤时兜底点最低，避免对话卡死
        val clickable = rs.filter { item ->
            val t = item.text ?: return@filter true
            !optionKeywords.pause.any { t.contains(it) }
        }
        if (clickable.isEmpty()) {
            events?.onAutoSkipLog("全部选项命中暂停词，点最低项")
            return Decision(rs.last())
        }
        for (item in clickable) {
            val t = item.text ?: continue
            if (optionKeywords.select.any { t.contains(it) }) {
                events?.onAutoSkipLog("关键词命中：$t")
                return Decision(item)
            }
        }
        for (item in clickable) {
            val t = item.text ?: continue
            if (settings.orangeOptionEnabled && isOrangeOption(region, item)) {
                events?.onAutoSkipLog("橙色关键选项：$t")
                return Decision(item)
            }
        }
        events?.onAutoSkipLog("无关键词命中，点最低选项")
        return Decision(clickable.last())
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
        private const val IDLE_LOG_INTERVAL_MS = 5000L
        private const val ORANGE_RATE_MIN = 0.06
        private const val FOREGROUND_CHECK_INTERVAL_MS = 500L
        private const val SKIP_LOG_INTERVAL_MS = 5000L
        private const val DIALOGUE_EXIT_CONFIRM_COUNT = 2
        private const val DIALOGUE_TEXT_MIN_LEN = 6
        private const val DIALOGUE_TEXT_MAX_LINES = 4
        private const val CHAT_ICON_CHECK_INTERVAL_MS = 500L

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
