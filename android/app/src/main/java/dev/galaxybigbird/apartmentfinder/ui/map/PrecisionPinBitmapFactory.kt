package dev.galaxybigbird.apartmentfinder.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max

/**
 * Renders the exact iOS Build 31.1 zero-teardrop precision delivery pin onto a high-DPI Bitmap:
 *  - Floating dark-glass capsule with circular accent stop-sequence badge, optional yellow warning
 *    triangle (when estimated), bold unit · building label, and accent selected border.
 *  - Dark-cased 2dp hairline gradient stem connecting seamlessly into a crisp precision target
 *    anchor dot (dark rim + white ring + vivid core + selected translucent glow halo).
 *  - Bottom center of the bitmap sits flush with the precision target anchor dot so MapLibre's
 *    ICON_ANCHOR_BOTTOM places the anchor dot dead-on the building access wall / door coordinate.
 */
object PrecisionPinBitmapFactory {

    private const val COLOR_ORANGE = 0xFFFF9500.toInt()
    private const val COLOR_TEAL = 0xFF14B8A6.toInt()
    private const val COLOR_GRAY = 0xFF8E8E93.toInt()
    private const val COLOR_YELLOW = 0xFFFACC15.toInt()
    private const val COLOR_PURPLE = 0xFFA855F7.toInt()

    fun createDeliveryPinBitmap(
        density: Float,
        sequence: String,
        primaryLabel: String,
        ready: Boolean,
        completed: Boolean,
        selected: Boolean,
        estimated: Boolean
    ): Bitmap {
        val dp = density.coerceIn(1.5f, 4.0f)
        val accent = when {
            completed -> COLOR_GRAY
            ready -> COLOR_TEAL
            else -> COLOR_ORANGE
        }
        val isMuted = completed

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMuted && !selected) 0xD1FFFFFF.toInt() else Color.WHITE
            textSize = 12.5f * dp
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val seqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMuted) Color.WHITE else Color.BLACK
            textSize = (if (sequence.length > 2) 9.5f else 11f) * dp
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val badgeDiameter = 20f * dp
        val padStart = 4.5f * dp
        val padEnd = 8.5f * dp
        val padVert = 4f * dp
        val gap = 5f * dp
        val warnSize = if (estimated) 11f * dp else 0f
        val warnGap = if (estimated) 4f * dp else 0f
        val labelWidth = labelPaint.measureText(primaryLabel)

        val pillWidth = max(48f * dp, padStart + badgeDiameter + gap + warnSize + warnGap + labelWidth + padEnd)
        val pillHeight = badgeDiameter + padVert * 2f
        val shadowPad = 5f * dp
        val stemHeight = 14f * dp
        val dotOuterR = 6f * dp
        val haloR = 9f * dp

        val totalWidth = ceil((pillWidth + shadowPad * 2f).toDouble()).toInt().coerceAtLeast(1)
        val totalHeight = ceil((shadowPad + pillHeight + stemHeight + haloR).toDouble()).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = totalWidth / 2f
        val pillTop = shadowPad
        val pillLeft = cx - pillWidth / 2f
        val pillRect = RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)
        val pillRadius = pillHeight / 2f

        // 1. Hairline pointer stem (dark outer casing + bright gradient core)
        val stemTop = pillRect.bottom - 1f * dp
        val dotCy = totalHeight - haloR
        val stemCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMuted && !selected) 0x99000000.toInt() else 0xD9000000.toInt()
            strokeWidth = 4f * dp
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx, stemTop, cx, dotCy, stemCasingPaint)

        val stemCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = (if (selected) 2.2f else 1.8f) * dp
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(
                cx, stemTop, cx, dotCy,
                if (selected) accent else (if (isMuted) 0xB3FFFFFF.toInt() else 0xF2FFFFFF.toInt()),
                accent,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawLine(cx, stemTop + 1f * dp, cx, dotCy, stemCorePaint)

        // 2. Precision target anchor dot (glow halo when selected + dark rim + white ring + vivid core)
        if (selected) {
            val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (accent and 0x00FFFFFF) or 0x4D000000
                style = Paint.Style.FILL
            }
            val haloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (accent and 0x00FFFFFF) or 0xF2000000.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * dp
            }
            canvas.drawCircle(cx, dotCy, haloR - 1f * dp, haloFill)
            canvas.drawCircle(cx, dotCy, haloR - 1f * dp, haloStroke)
        }
        val darkRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selected) 0xE0000000.toInt() else (if (isMuted) 0x94000000.toInt() else 0xBF000000.toInt())
        }
        val whiteRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMuted && !selected) 0xD9FFFFFF.toInt() else Color.WHITE
        }
        val coreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
        }
        canvas.drawCircle(cx, dotCy, dotOuterR, darkRimPaint)
        canvas.drawCircle(cx, dotCy, 5f * dp, whiteRingPaint)
        canvas.drawCircle(cx, dotCy, (if (selected) 3.6f else 3.2f) * dp, coreDotPaint)

        // 3. Floating dark-glass capsule with drop shadow & accent border
        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                selected -> 0xF50A0E14.toInt()
                isMuted -> 0xCC0A0E14.toInt()
                else -> 0xE60A0E14.toInt()
            }
            setShadowLayer(
                (if (selected) 4f else 2.5f) * dp,
                0f,
                1.5f * dp,
                if (selected) 0x8C000000.toInt() else 0x61000000.toInt()
            )
        }
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillBgPaint)

        val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (selected) 2.2f else 1.1f) * dp
            color = when {
                selected -> accent
                isMuted -> 0x59FFFFFF
                else -> 0x94FFFFFF.toInt()
            }
        }
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillBorderPaint)

        // 4. Circular stop-sequence badge
        val badgeCx = pillRect.left + padStart + badgeDiameter / 2f
        val badgeCy = pillRect.centerY()
        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * dp
            color = if (selected) 0xF2FFFFFF.toInt() else 0xA6FFFFFF.toInt()
        }
        canvas.drawCircle(badgeCx, badgeCy, badgeDiameter / 2f, badgeBg)
        canvas.drawCircle(badgeCx, badgeCy, badgeDiameter / 2f, badgeStroke)

        val seqFm = seqPaint.fontMetrics
        val seqBaseline = badgeCy - (seqFm.ascent + seqFm.descent) / 2f
        canvas.drawText(sequence, badgeCx, seqBaseline, seqPaint)

        // 5. Optional yellow warning triangle for estimated pins
        var cursorX = pillRect.left + padStart + badgeDiameter + gap
        if (estimated) {
            val triPath = Path().apply {
                val half = warnSize / 2f
                moveTo(cursorX + half, badgeCy - half * 0.85f)
                lineTo(cursorX + warnSize, badgeCy + half * 0.85f)
                lineTo(cursorX, badgeCy + half * 0.85f)
                close()
            }
            val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_YELLOW }
            canvas.drawPath(triPath, triPaint)
            cursorX += warnSize + warnGap
        }

        // 6. Bold unit · building label
        val lblFm = labelPaint.fontMetrics
        val lblBaseline = badgeCy - (lblFm.ascent + lblFm.descent) / 2f
        canvas.drawText(primaryLabel, cursorX, lblBaseline, labelPaint)

        return bitmap
    }

    fun createLockerPinBitmap(density: Float): Bitmap {
        val dp = density.coerceIn(1.5f, 4.0f)
        val size = ceil((34f * dp).toDouble()).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_PURPLE }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * dp
        }
        canvas.drawCircle(cx, cy, 13f * dp, bg)
        canvas.drawCircle(cx, cy, 13f * dp, ring)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * dp
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = txt.fontMetrics
        canvas.drawText("LK", cx, cy - (fm.ascent + fm.descent) / 2f, txt)
        return bmp
    }
}
