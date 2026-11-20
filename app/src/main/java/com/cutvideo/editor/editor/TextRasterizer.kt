package com.cutvideo.editor.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.cutvideo.editor.domain.model.TextAlign
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.ui.theme.FontFamilies

/**
 * Rasterises a [TextSpec] into a bitmap for the GPU overlay pass.
 *
 * The Compose preview draws the same spec with the same typefaces, so the text a user places is the
 * text that lands in the exported file.
 */
object TextRasterizer {

    /**
     * @param frameWidth/Height output frame size, used to resolve the relative text size.
     * @return a tightly cropped ARGB bitmap plus its anchor position, or null when the text is empty.
     */
    fun rasterize(spec: TextSpec, frameWidth: Int, frameHeight: Int): RasterizedText? {
        if (spec.text.isBlank()) return null
        if (frameWidth <= 0 || frameHeight <= 0) return null
        // Text bitmaps are capped so a 4K export can never allocate a frame-sized ARGB buffer.
        return runCatching { rasterizeInternal(spec, frameWidth, frameHeight) }.getOrNull()
    }

    private const val MAX_TEXT_PX = 2048

    private fun rasterizeInternal(spec: TextSpec, frameWidth: Int, frameHeight: Int): RasterizedText? {
        // sizeSp is relative to a 1080-wide reference frame so the same spec scales across exports.
        val referenceShortSide = 1080f
        val pixelSize = spec.sizeSp * (frameWidth.coerceAtMost(frameHeight) / referenceShortSide)
            .coerceAtLeast(0.25f)

        val shadowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (spec.shadow.enabled) spec.shadow.color else Color.TRANSPARENT
            textSize = pixelSize
            typeface = typefaceFor(spec)
            letterSpacing = spec.letterSpacing
            textAlign = Paint.Align.LEFT
            setShadowLayer(spec.shadow.radius, spec.shadow.dx, spec.shadow.dy, spec.shadow.color)
        }
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.color
            textSize = pixelSize
            typeface = typefaceFor(spec)
            letterSpacing = spec.letterSpacing
            if (spec.underline) {
                isUnderlineText = true
            }
            if (spec.outline.enabled) {
                style = Paint.Style.STROKE
                strokeWidth = spec.outline.width
                this.color = spec.outline.color
            }
        }

        val lines = spec.text.split("\n")
        val metrics = fillPaint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * spec.lineHeightMultiplier
        val maxWidth = frameWidth * spec.maxWidthFrac
        val totalHeight = lineHeight * lines.size
        val padding = spec.backgroundPaddingDp * (frameWidth / 400f)
        val shadowPad = if (spec.shadow.enabled) spec.shadow.radius * 2f else 0f

        val width = (maxWidth + padding * 2 + shadowPad).toInt().coerceIn(8, frameWidth).coerceAtMost(MAX_TEXT_PX)
        val height = (totalHeight + padding * 2 + shadowPad).toInt().coerceIn(8, frameHeight).coerceAtMost(MAX_TEXT_PX)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (Color.alpha(spec.backgroundColor) > 0) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.backgroundColor
                style = Paint.Style.FILL
            }
            val radius = padding * 0.8f
            canvas.drawRoundRect(
                RectF(shadowPad / 2, shadowPad / 2, width - shadowPad / 2, height - shadowPad / 2),
                radius,
                radius,
                bgPaint
            )
        }

        var baseline = padding + shadowPad / 2 - metrics.ascent
        lines.forEach { line ->
            val textWidth = fillPaint.measureText(line)
            val x = when (spec.align) {
                TextAlign.LEFT -> padding + shadowPad / 2
                TextAlign.CENTER -> (width - textWidth) / 2f
                TextAlign.RIGHT -> width - textWidth - padding - shadowPad / 2
            }
            if (spec.outline.enabled) {
                val outlinePaint = TextPaint(fillPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = spec.outline.width
                    color = spec.outline.color
                }
                canvas.drawText(line, x, baseline, outlinePaint)
            }
            if (spec.shadow.enabled) {
                canvas.drawText(line, x, baseline, shadowPaint)
            }
            canvas.drawText(line, x, baseline, fillPaint)
            baseline += lineHeight
        }

        return RasterizedText(
            bitmap = bitmap,
            centerXFrac = spec.xFrac,
            centerYFrac = spec.yFrac,
            widthFrac = width.toFloat() / frameWidth,
            heightFrac = height.toFloat() / frameHeight,
            rotationDegrees = spec.rotationDegrees
        )
    }

    fun typefaceFor(spec: TextSpec): Typeface {
        val base = FontFamilies.typefaceFor(spec.fontId)
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    data class RasterizedText(
        val bitmap: Bitmap,
        val centerXFrac: Float,
        val centerYFrac: Float,
        val widthFrac: Float,
        val heightFrac: Float,
        val rotationDegrees: Float
    )
}
