package com.iamfaizankhalid.eyecare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/** Circular 20-second countdown with a ring that drains as time passes. */
class CountdownRingView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x1FFFFFFF
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.accent
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textPrimary
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textTertiary
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.18f
    }

    private var remainingFraction = 1f
    private var seconds = 20

    fun setRemaining(remainingMs: Long, totalMs: Long) {
        val fraction = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        val newSeconds = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)
        if (abs(fraction - remainingFraction) > 0.002f || newSeconds != seconds) {
            remainingFraction = fraction
            seconds = newSeconds
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val strokeWidth = 4.5f * density
        val radius = min(width, height) / 2f - strokeWidth

        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth

        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(ring, -90f, 360f, false, trackPaint)
        canvas.drawArc(ring, -90f, 360f * remainingFraction, false, progressPaint)

        numberPaint.textSize = radius * 0.92f
        val numberCenterY = cy - radius * 0.10f
        val numberBaseline = numberCenterY - (numberPaint.ascent() + numberPaint.descent()) / 2f
        canvas.drawText(seconds.toString(), cx, numberBaseline, numberPaint)

        labelPaint.textSize = radius * 0.13f
        canvas.drawText("SECONDS", cx, cy + radius * 0.52f, labelPaint)
    }
}
