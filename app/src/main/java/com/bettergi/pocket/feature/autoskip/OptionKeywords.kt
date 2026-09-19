package com.bettergi.pocket.feature.autoskip

import android.content.res.AssetManager
import org.json.JSONArray

/**
 * 对话选项关键词决策表（对齐 PC 版 BetterGI 的 select/pause_options.json）。
 * [select] 命中的选项优先点击；[pause] 命中的选项不点击。
 */
class OptionKeywords(
    val select: List<String> = emptyList(),
    val pause: List<String> = emptyList(),
) {
    companion object {
        fun load(assets: AssetManager): OptionKeywords {
            return OptionKeywords(
                select = loadList(assets, "recognition/AutoSkip/select_options.json"),
                pause = loadList(assets, "recognition/AutoSkip/pause_options.json"),
            )
        }

        private fun loadList(assets: AssetManager, path: String): List<String> {
            return try {
                val json = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                val arr = JSONArray(json)
                List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
