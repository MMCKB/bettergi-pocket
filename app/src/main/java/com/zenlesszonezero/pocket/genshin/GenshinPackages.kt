package com.zenlesszonezero.pocket.genshin

/**
 * 目标游戏包名识别。
 *
 * 注：类名沿用 `GenshinPackages`（历史命名），但内容已改为**绝区零**（Zenless Zone Zero）。
 * 这样无需改动 GenshinLauncher / GenshinLaunchMonitor / InputAccessibilityService 等调用方。
 */
object GenshinPackages {
    val CANDIDATES: List<String> = listOf(
        "com.miHoYo.zenlessZoneZero",           // 国服官服
        "com.HoYoverse.zenlessZoneZero",        // 国际服
        "com.miHoYo.cloudgames.zenlessZoneZero", // 云·绝区零
        "com.miHoYo.cloudgames.ZZZ",
    )

    fun isGenshinPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (CANDIDATES.any { it.equals(packageName, ignoreCase = true) }) return true
        val lower = packageName.lowercase()
        return lower.contains("zenlesszonezero") ||
            lower.contains("zenless") ||
            lower.startsWith("com.mihoyo.zzz") ||
            lower == "com.mihoyo.cloudgames.zzz"
    }

    fun pickPreferred(installed: Collection<String>): String? {
        val set = installed.toSet()
        CANDIDATES.firstOrNull { it in set }?.let { return it }
        return installed.firstOrNull { isGenshinPackage(it) }
    }

    fun displayName(packageName: String): String = when (packageName) {
        "com.miHoYo.zenlessZoneZero" -> "国服官服"
        "com.HoYoverse.zenlessZoneZero" -> "国际服"
        "com.miHoYo.cloudgames.zenlessZoneZero" -> "云·绝区零"
        "com.miHoYo.cloudgames.ZZZ" -> "云·绝区零"
        else -> "绝区零"
    }

    fun shouldAttemptAutoLaunch(
        enabled: Boolean,
        genshinInForeground: Boolean?,
        alreadyAttempted: Boolean,
        allowed: Boolean,
    ): Boolean {
        if (!enabled || !allowed || alreadyAttempted) return false
        return genshinInForeground != true
    }
}
