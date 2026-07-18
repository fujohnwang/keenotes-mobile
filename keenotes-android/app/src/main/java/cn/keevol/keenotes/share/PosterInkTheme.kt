package cn.keevol.keenotes.share

import androidx.annotation.DrawableRes
import cn.keevol.keenotes.R
import kotlin.math.roundToInt

enum class PosterInkTheme(
    @DrawableRes val drawableRes: Int,
    val label: String
) {
    MEI(R.drawable.poster_ink_mei, "梅"),
    LAN(R.drawable.poster_ink_lan, "兰"),
    ZHU(R.drawable.poster_ink_zhu, "竹"),
    JU(R.drawable.poster_ink_ju, "菊");

    fun next(): PosterInkTheme {
        val values = entries.toTypedArray()
        return values[(ordinal + 1) % values.size]
    }

    fun alphaFor(contentLength: Int): Int {
        val opacity = when {
            contentLength > 900 -> 0.07f
            contentLength > 500 -> 0.09f
            contentLength > 220 -> 0.10f
            else -> 0.11f
        }
        return (opacity * 255).roundToInt().coerceIn(0, 255)
    }

    companion object {
        fun stableForNoteId(noteId: Long): PosterInkTheme {
            val values = entries.toTypedArray()
            val index = Math.floorMod(noteId, values.size.toLong()).toInt()
            return values[index]
        }
    }
}
