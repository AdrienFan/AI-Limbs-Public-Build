package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mobile RGB/HSV selector. Krita's advanced selector uses a hue ring and an HSV
 * triangle; the Android view keeps that geometry while writing the existing
 * Art Studio #AARRGGBB brush color. It does not copy Krita's Qt color pipeline.
 */
internal class StudioColorSelector(context: Context) : View(context) {
    var onColorSelected: (Int) -> Unit = {}
    var selectedColor: Int = Color.rgb(22, 22, 22)
        set(value) {
            if (field == value) return
            field = value
            val rememberedHue = hsv[0]
            Color.colorToHSV(value, hsv)
            if (hsv[1] == 0f) hsv[0] = rememberedHue
            invalidate()
        }

    private val hsv = floatArrayOf(0f, 0f, 22f / 255f)
    private val density = resources.displayMetrics.density
    private val ringWidth = 16f * density
    private val barHeight = 13f * density
    private val gap = 10f * density
    private val margin = 8f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trianglePath = Path()
    private var triangleBitmap: Bitmap? = null
    private var cachedHue = -1f
    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    private var triangleWidth = 0f
    private var triangleHeight = 0f
    private var triangleLeft = 0f
    private var triangleTop = 0f
    private var firstBar = 0f
    private var secondBar = 0f
    private var activePart = 0

    init {
        contentDescription = "多功能拾色器：色相环、饱和度和明度三角区、两条调节色条"
    }

    private fun updateGeometry() {
        val diameter = min(width - 2f * margin, height - 3f * gap - 2f * barHeight - 2f * margin)
            .coerceAtLeast(0f)
        centerX = width / 2f
        centerY = margin + diameter / 2f
        outerRadius = diameter / 2f - ringWidth / 2f
        innerRadius = outerRadius - ringWidth / 2f
        triangleHeight = innerRadius * 1.37f
        triangleWidth = triangleHeight * 2f / sqrt(3f)
        triangleLeft = centerX - triangleWidth / 2f
        triangleTop = centerY - triangleHeight * 2f / 3f
        firstBar = margin + diameter + gap
        secondBar = firstBar + barHeight + gap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGeometry()
        if (innerRadius <= 0f) return

        val wheelColors = IntArray(13) { index ->
            Color.HSVToColor(floatArrayOf(index * 30f, 1f, 1f))
        }
        val wheel = SweepGradient(centerX, centerY, wheelColors, null)
        wheel.setLocalMatrix(Matrix().apply { setRotate(180f, centerX, centerY) })
        paint.shader = wheel
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        canvas.drawCircle(centerX, centerY, outerRadius, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL

        drawTriangle(canvas)
        val angle = Math.toRadians((hsv[0] - 180f).toDouble())
        val markerX = centerX + outerRadius * kotlin.math.cos(angle).toFloat()
        val markerY = centerY + outerRadius * kotlin.math.sin(angle).toFloat()
        drawMarker(canvas, markerX, markerY)
        val value = hsv[2]
        val markerTriangleX = centerX - triangleWidth * value / 2f + triangleWidth * value * hsv[1]
        val markerTriangleY = triangleTop + triangleHeight * value
        drawMarker(canvas, markerTriangleX, markerTriangleY)

        val left = margin
        val right = width - margin
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, value))
        val currentBright = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
        drawBar(canvas, left, right, firstBar,
            Color.HSVToColor(floatArrayOf(hsv[0], 0f, value)), hueColor, hsv[1])
        drawBar(canvas, left, right, secondBar, Color.BLACK, currentBright, value)
    }

    private fun drawTriangle(canvas: Canvas) {
        val bitmapWidth = triangleWidth.roundToInt().coerceAtLeast(2)
        val bitmapHeight = triangleHeight.roundToInt().coerceAtLeast(2)
        if (triangleBitmap?.width != bitmapWidth || triangleBitmap?.height != bitmapHeight ||
            abs(cachedHue - hsv[0]) > 0.2f) {
            triangleBitmap?.recycle()
            val pixels = IntArray(bitmapWidth * bitmapHeight)
            val sample = floatArrayOf(hsv[0], 0f, 0f)
            for (y in 0 until bitmapHeight) {
                val value = ((y + 0.5f) / bitmapHeight).coerceIn(0f, 1f)
                val span = bitmapWidth * value
                val from = (bitmapWidth - span) / 2f
                for (x in 0 until bitmapWidth) {
                    if (x + 0.5f >= from && x + 0.5f <= from + span) {
                        sample[1] = ((x + 0.5f - from) / span).coerceIn(0f, 1f)
                        sample[2] = value
                        pixels[y * bitmapWidth + x] = Color.HSVToColor(sample)
                    }
                }
            }
            triangleBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                .also { it.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight) }
            cachedHue = hsv[0]
        }
        paint.style = Paint.Style.FILL
        paint.shader = null
        canvas.drawBitmap(triangleBitmap!!, triangleLeft, triangleTop, paint)
        trianglePath.reset()
        trianglePath.moveTo(centerX, triangleTop)
        trianglePath.lineTo(triangleLeft + triangleWidth, triangleTop + triangleHeight)
        trianglePath.lineTo(triangleLeft, triangleTop + triangleHeight)
        trianglePath.close()
        paint.color = Color.argb(150, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawPath(trianglePath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float) {
        paint.shader = null
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        canvas.drawCircle(x, y, 5f * density, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 1.5f * density
        canvas.drawCircle(x, y, 5f * density, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBar(canvas: Canvas, left: Float, right: Float, top: Float,
                        start: Int, end: Int, position: Float) {
        paint.shader = LinearGradient(left, top, right, top, start, end, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(left, top, right, top + barHeight), 3f * density, 3f * density, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.strokeWidth = 2f * density
        canvas.drawLine(left + (right - left) * position, top - 2f * density,
            left + (right - left) * position, top + barHeight + 2f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            updateGeometry()
            val distance = hypot(event.x - centerX, event.y - centerY)
            activePart = when {
                event.y in (firstBar - gap / 2f)..(firstBar + barHeight + gap / 2f) -> 3
                event.y in (secondBar - gap / 2f)..(secondBar + barHeight + gap / 2f) -> 4
                distance in (innerRadius - 3f * density)..(outerRadius + ringWidth) -> 1
                event.y in triangleTop..(triangleTop + triangleHeight) &&
                    event.x in triangleLeft..(triangleLeft + triangleWidth) -> 2
                else -> 0
            }
            if (activePart == 0) return false
            parent.requestDisallowInterceptTouchEvent(true)
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_DOWN) {
            when (activePart) {
                1 -> {
                    hsv[0] = ((Math.toDegrees(atan2(event.y - centerY, event.x - centerX)
                        .toDouble()).toFloat() + 180f) % 360f + 360f) % 360f
                    hsv[1] = 1f
                    hsv[2] = 1f
                }
                2 -> {
                    hsv[2] = ((event.y - triangleTop) / triangleHeight).coerceIn(0f, 1f)
                    val span = triangleWidth * hsv[2]
                    val from = centerX - span / 2f
                    hsv[1] = if (span > 0f) ((event.x - from) / span).coerceIn(0f, 1f) else 0f
                }
                3 -> hsv[1] = ((event.x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
                4 -> hsv[2] = ((event.x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
            }
            if (activePart != 0) {
                val value = Color.HSVToColor(Color.alpha(selectedColor), hsv)
                selectedColor = value
                onColorSelected(value)
                invalidate()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activePart = 0
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        triangleBitmap?.recycle()
        triangleBitmap = null
        super.onDetachedFromWindow()
    }
}
