package com.zenlesszonezero.pocket.feature.autoskip

import com.zenlesszonezero.pocket.recognition.CaptureContent
import com.zenlesszonezero.pocket.recognition.IntRect
import com.zenlesszonezero.pocket.recognition.RecognitionObject
import com.zenlesszonezero.pocket.recognition.area.Region

/**
 * 绝区零对话判定（独立函数，[AutoSkipFeature] 内联了同样逻辑）：
 * 右上角功能按钮（跳过/自动/回顾/菜单）命中，或底部对话气泡有文字，即视为处于对话。
 *
 * 说明：绝区零没有原神那样的「对话历史」图标，因此不再使用模板匹配，
 * 改为 1080p 归一化比例区域 + ML Kit 中文 OCR 判定。
 */
fun isDialogueScene(content: CaptureContent): Boolean {
    val cw = content.captureRectArea.width
    val ch = content.captureRectArea.height
    if (cw <= 0 || ch <= 0) return false
    val top = ocr(content, (cw * 0.60).toInt(), (ch * 0.01).toInt(), (cw * 0.38).toInt(), (ch * 0.12).toInt())
    if (top.any { hasDialogueButton(it.text) }) return true
    val bottom = ocr(content, (cw * 0.06).toInt(), (ch * 0.70).toInt(), (cw * 0.88).toInt(), (ch * 0.24).toInt())
    return bottom.any { !it.text.isNullOrBlank() }
}

private fun hasDialogueButton(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    return text.contains("跳过") || text.contains("自动") ||
        text.contains("回顾") || text.contains("菜单")
}

private fun ocr(content: CaptureContent, x: Int, y: Int, w: Int, h: Int): List<Region> {
    if (w <= 0 || h <= 0) return emptyList()
    val ro = RecognitionObject.ocrThis()
    ro.regionOfInterest = IntRect(x, y, w, h)
    return content.findMulti(ro)
}
