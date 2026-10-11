package cn.keevol.keenotes.share

import android.graphics.Color

/** Local to the poster dialog; never persisted as an app preference. */
enum class PosterColorTheme(val label: String, val background: Int, val text: Int, private val secondary: Int) {
    PAPER("纸间 · 暖白", 0xFFFDFCFB.toInt(), 0xFF1A1816.toInt(), 0xFF1A1816.toInt()),
    KLEIN("洞见 · 克莱因蓝", 0xFF002FA7.toInt(), Color.WHITE, 0xFFB7C9F1.toInt()),
    MIDNIGHT("深思 · 深夜蓝", 0xFF111C30.toInt(), 0xFFF4F6FB.toInt(), 0xFFAEBBCC.toInt()),
    PINE("沉淀 · 松墨绿", 0xFF123A35.toInt(), 0xFFF2F3EB.toInt(), 0xFFB0C5BC.toInt());

    val isDark get() = this != PAPER
    val footer get() = if (isDark) secondary else Color.argb(88, 26, 24, 22)
    val badgeText get() = if (isDark) secondary else Color.argb(148, 26, 24, 22)
    val badgeFill get() = if (isDark) Color.argb(12, 255, 255, 255) else Color.argb(12, 0, 0, 0)
    val badgeStroke get() = if (isDark) Color.argb(36, 255, 255, 255) else Color.argb(14, 0, 0, 0)
    val divider get() = if (isDark) Color.argb(38, 255, 255, 255) else Color.argb(15, 0, 0, 0)
    val outline get() = Color.argb(if (isDark) 32 else 210, 255, 255, 255)

    fun offset(delta: Int): PosterColorTheme {
        val themes = values()
        return themes[Math.floorMod(ordinal + delta, themes.size)]
    }
}
