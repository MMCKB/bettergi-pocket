package com.zenlesszonezero.pocket.feature.autoskip

interface AutoSkipEvents {
    fun onTalkHistoryMatched()

    /** 绝区零：判定命中对话界面（右上角功能按钮 或 底部对话气泡有文字）。 */
    fun onDialogueMatched() = Unit

    fun onChatIconsRecognized(count: Int, topX: Int, topY: Int)
    fun onChatIconClicked(x: Int, y: Int)

    /** 通用日志行（黑屏、感叹号、OCR 决策等新增动作都走这里）。 */
    fun onAutoSkipLog(message: String) = Unit

    fun onBlackScreenClicked(x: Int, y: Int) = Unit

    /** OCR 读到的选项文字列表。 */
    fun onOptionTextsRecognized(texts: List<String>) = Unit

    /** pause 关键词拦截的选项文字。 */
    fun onPauseBlocked(text: String) = Unit

    /** 空闲扫描心跳（未检测到对话时周期性输出）。 */
    fun onIdleScan() = Unit
}
