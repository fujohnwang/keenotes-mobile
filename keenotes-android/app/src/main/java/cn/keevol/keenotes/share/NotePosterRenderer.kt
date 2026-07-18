package cn.keevol.keenotes.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.util.DateTimeUtil
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object NotePosterRenderer {
    const val EXPORT_WIDTH = 1080
    const val VIDEO_WIDTH = 1080
    const val VIDEO_HEIGHT = 1920
    const val ASPECT_RATIO = 9f / 16f

    private val paperColor = Color.rgb(0xFD, 0xFC, 0xFB)
    private val inkText = Color.rgb(26, 24, 22)
    private val footerText = Color.argb(88, 26, 24, 22)
    private val badgeFill = Color.argb(12, 0, 0, 0)
    private val badgeStroke = Color.argb(14, 0, 0, 0)
    private val divider = Color.argb(15, 0, 0, 0)

    fun renderPosterImage(
        context: Context,
        note: Note,
        hiddenMessage: String,
        inkTheme: PosterInkTheme,
        width: Int = EXPORT_WIDTH
    ): Bitmap {
        return renderPosterImage(
            context = context,
            noteContent = note.content,
            posterDate = formatPosterDate(note.createdAt),
            hiddenMessage = hiddenMessage,
            inkTheme = inkTheme,
            width = width
        )
    }

    fun renderPosterImage(
        context: Context,
        noteContent: String,
        posterDate: String,
        hiddenMessage: String,
        inkTheme: PosterInkTheme,
        width: Int = EXPORT_WIDTH
    ): Bitmap {
        val content = noteContent
        val author = hiddenMessage.trim().takeIf { it.isNotEmpty() }
        val contentLength = content.codePointCount(0, content.length)
        val scale = width / 390f

        val cardPadding = scaled(if (contentLength > 700) 28 else 34, scale)
        val contentVerticalPadding = scaled(resolveContentVerticalPadding(contentLength), scale)
        val footerVerticalPadding = scaled(if (contentLength > 700) 18 else 22, scale)
        val contentFontSize = scaled(resolveContentFontSize(contentLength), scale).toFloat()
        val contentLineSpacing = scaled(resolveContentLineSpacing(contentLength), scale).toFloat()
        val footerFontSize = scaled(11, scale).toFloat()

        val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkText
            textSize = contentFontSize
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = footerText
            textSize = footerFontSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(148, 26, 24, 22)
            textSize = footerFontSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val textWidth = width - cardPadding * 2
        val layout = buildLayout(content, contentPaint, textWidth, contentLineSpacing)
        val textHeight = if (content.isEmpty()) {
            val metrics = contentPaint.fontMetricsInt
            metrics.descent - metrics.ascent
        } else {
            layout.height
        }
        val footerTextHeight = run {
            val metrics = footerPaint.fontMetricsInt
            metrics.descent - metrics.ascent
        }

        val footerHeight = footerTextHeight + footerVerticalPadding * 2
        val dividerHeight = max(1, scaled(1, scale))
        val minimumHeight = exportHeightFor(width)
        val requiredHeight = contentVerticalPadding * 2 + textHeight + dividerHeight + footerHeight
        val height = max(minimumHeight, requiredHeight)
        val extraVerticalSpace = height - requiredHeight
        val textY = contentVerticalPadding + extraVerticalSpace / 2
        val footerY = height - footerHeight

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cornerRadius = scaled(32, scale).toFloat()
        val posterRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val posterPath = Path().apply {
            addRoundRect(posterRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(posterPath)
        canvas.drawColor(paperColor)
        paintPaperGrain(canvas, width, height, scale)
        paintInkTheme(context, canvas, inkTheme, contentLength, width, height)
        paintVignette(canvas, width, height)
        paintContent(canvas, layout, content, cardPadding, textY)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = divider }
        canvas.drawRect(0f, footerY.toFloat(), width.toFloat(), (footerY + dividerHeight).toFloat(), dividerPaint)
        paintFooter(canvas, author, posterDate, footerPaint, badgePaint, cardPadding, footerY, footerVerticalPadding, width)
        canvas.restore()

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 0.8f * scale)
        }
        canvas.drawRoundRect(posterRect, cornerRadius, cornerRadius, outlinePaint)

        return bitmap
    }

    fun renderVideoFrame(posterImage: Bitmap): Bitmap {
        val frame = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val frameRect = RectF(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat())
        canvas.drawColor(paperColor)

        val smallBackground = Bitmap.createBitmap(270, 480, Bitmap.Config.ARGB_8888)
        Canvas(smallBackground).apply {
            drawColor(paperColor)
            drawBitmapCover(posterImage, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG))
        }
        val blurred = boxBlur(smallBackground, 10)
        canvas.drawBitmap(blurred, null, frameRect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        })
        smallBackground.recycle()
        blurred.recycle()

        val scale = min(VIDEO_WIDTH / posterImage.width.toFloat(), VIDEO_HEIGHT / posterImage.height.toFloat())
        val foregroundWidth = posterImage.width * scale
        val foregroundHeight = posterImage.height * scale
        val left = (VIDEO_WIDTH - foregroundWidth) / 2f
        val top = (VIDEO_HEIGHT - foregroundHeight) / 2f
        canvas.drawBitmap(
            posterImage,
            null,
            RectF(left, top, left + foregroundWidth, top + foregroundHeight),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        )
        return frame
    }

    fun formatPosterDate(createdAt: String): String {
        val local = DateTimeUtil.utcToLocalDisplay(createdAt)
        return if (local.length >= 10) local.substring(0, 10) else local
    }

    fun exportHeightFor(width: Int): Int = ceil(width / ASPECT_RATIO).toInt()

    private fun buildLayout(text: String, paint: TextPaint, width: Int, lineSpacing: Float): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(lineSpacing, 1f)
            .build()
    }

    private fun paintPaperGrain(canvas: Canvas, width: Int, height: Int, scale: Float) {
        val step = max(1, scaled(7, scale))
        val dotSize = max(1f, scaled(1, scale).toFloat())
        val paint = Paint()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val seed = (x * 31 + y * 17).toFloat().roundToInt()
                val alpha = if (seed % 5 == 0) 7 else 4
                paint.color = Color.argb(alpha, 0, 0, 0)
                canvas.drawRect(x.toFloat(), y.toFloat(), x + dotSize, y + dotSize, paint)
                x += step
            }
            y += step
        }
    }

    private fun paintInkTheme(
        context: Context,
        canvas: Canvas,
        theme: PosterInkTheme,
        contentLength: Int,
        width: Int,
        height: Int
    ) {
        val ink = BitmapFactory.decodeResource(context.resources, theme.drawableRes) ?: return
        val inkHeight = exportHeightFor(width)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = theme.alphaFor(contentLength)
            blendMode = BlendMode.MULTIPLY
            isFilterBitmap = true
        }
        try {
            canvas.drawBitmap(
                ink,
                null,
                Rect(0, height - inkHeight, width, height),
                paint
            )
        } finally {
            ink.recycle()
        }
    }

    private fun paintVignette(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(8, 0, 0, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun paintContent(canvas: Canvas, layout: StaticLayout, content: String, x: Int, y: Int) {
        if (content.isEmpty()) return
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun paintFooter(
        canvas: Canvas,
        author: String?,
        posterDate: String,
        footerPaint: TextPaint,
        badgePaint: TextPaint,
        cardPadding: Int,
        footerY: Int,
        footerVerticalPadding: Int,
        width: Int
    ) {
        val badgeText = "KeeNotes"
        val badgeMetrics = badgePaint.fontMetrics
        val badgeTextHeight = badgeMetrics.descent - badgeMetrics.ascent
        val badgeHorizontalPadding = max(10f, badgeTextHeight / 2f)
        val badgeVerticalPadding = max(5f, badgeTextHeight / 4f)
        val badgeWidth = badgePaint.measureText(badgeText) + badgeHorizontalPadding * 2f
        val badgeHeight = badgeTextHeight + badgeVerticalPadding * 2f
        val badgeX = width - cardPadding - badgeWidth
        val badgeY = footerY + footerVerticalPadding - badgeVerticalPadding
        val badgeRect = RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight)

        val badgeBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = badgeFill
            style = Paint.Style.FILL
        }
        val badgeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = badgeStroke
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgeBackground)
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgeOutline)
        canvas.drawText(
            badgeText,
            badgeX + badgeHorizontalPadding,
            badgeY + badgeVerticalPadding - badgeMetrics.ascent,
            badgePaint
        )

        val leftText = if (author == null) posterDate else "$author · $posterDate"
        val availableWidth = (badgeX - cardPadding - 12f).coerceAtLeast(0f)
        val ellipsized = TextUtils.ellipsize(leftText, footerPaint, availableWidth, TextUtils.TruncateAt.END).toString()
        val footerMetrics = footerPaint.fontMetrics
        val baseline = footerY + footerVerticalPadding - footerMetrics.ascent
        canvas.drawText(ellipsized, cardPadding.toFloat(), baseline, footerPaint)
    }

    private fun Canvas.drawBitmapCover(bitmap: Bitmap, dst: RectF, paint: Paint) {
        val scale = max(dst.width() / bitmap.width.toFloat(), dst.height() / bitmap.height.toFloat())
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = dst.left + (dst.width() - scaledWidth) / 2f
        val top = dst.top + (dst.height() - scaledHeight) / 2f
        drawBitmap(bitmap, null, RectF(left, top, left + scaledWidth, top + scaledHeight), paint)
    }

    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return source.copy(Bitmap.Config.ARGB_8888, false)
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val temp = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        blurHorizontal(input, temp, width, height, radius)
        blurVertical(temp, output, width, height, radius)
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun blurHorizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            val row = y * width
            for (i in -radius..radius) {
                val c = input[row + i.coerceIn(0, width - 1)]
                a += Color.alpha(c)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
            }
            for (x in 0 until width) {
                output[row + x] = Color.argb(a / window, r / window, g / window, b / window)
                val remove = input[row + (x - radius).coerceIn(0, width - 1)]
                val add = input[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += Color.alpha(add) - Color.alpha(remove)
                r += Color.red(add) - Color.red(remove)
                g += Color.green(add) - Color.green(remove)
                b += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    private fun blurVertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = input[i.coerceIn(0, height - 1) * width + x]
                a += Color.alpha(c)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(a / window, r / window, g / window, b / window)
                val remove = input[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += Color.alpha(add) - Color.alpha(remove)
                r += Color.red(add) - Color.red(remove)
                g += Color.green(add) - Color.green(remove)
                b += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    private fun resolveContentFontSize(contentLength: Int): Int = when {
        contentLength > 900 -> 16
        contentLength > 500 -> 18
        contentLength > 220 -> 20
        else -> 24
    }

    private fun resolveContentLineSpacing(contentLength: Int): Int = when {
        contentLength > 700 -> 8
        contentLength > 300 -> 10
        else -> 12
    }

    private fun resolveContentVerticalPadding(contentLength: Int): Int = when {
        contentLength > 900 -> 30
        contentLength > 500 -> 38
        else -> 52
    }

    private fun scaled(value: Int, scale: Float): Int = max(1, (value * scale).roundToInt())
}
