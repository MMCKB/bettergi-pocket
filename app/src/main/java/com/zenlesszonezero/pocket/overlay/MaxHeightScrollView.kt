package com.zenlesszonezero.pocket.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 高度受屏幕限制的 [ScrollView]：内容超出时可上下滚动。
 *
 * 用于悬浮球面板——横屏（平板 / PC / 模拟器）下屏幕较矮，面板内容会超出屏幕底部，
 * 普通 wrap_content 容器无法滚动，导致"不能下滑"。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenH = resources.displayMetrics.heightPixels
        val maxH = (screenH * MAX_HEIGHT_RATIO).toInt().coerceAtLeast(MIN_HEIGHT_PX)
        val capped = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.72f
        const val MIN_HEIGHT_PX = 240
    }
}
