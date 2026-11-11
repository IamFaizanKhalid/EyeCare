package com.iamfaizankhalid.eyecare

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.roundToInt

object Palette {
    val bg = 0xFF0A0A0C.toInt()
    val card = 0xFF151519.toInt()
    val stroke = 0xFF26262D.toInt()
    val textPrimary = 0xFFF2F2F5.toInt()
    val textSecondary = 0xFF9B9BA4.toInt()
    val textTertiary = 0xFF6D6D76.toInt()
    val accent = 0xFFA9E8B9.toInt()
    val accentInk = 0xFF08130B.toInt()
}

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

fun Context.roundedBg(
    color: Int,
    radiusDp: Int,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1,
): GradientDrawable {
    val corner = dp(radiusDp).toFloat()
    val stroke = dp(strokeWidthDp)
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = corner
        if (strokeColor != null) setStroke(stroke, strokeColor)
    }
}

fun ovalBg(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.rippled(
    color: Int,
    radiusDp: Int,
    strokeColor: Int? = null,
): RippleDrawable = RippleDrawable(
    ColorStateList.valueOf(0x33FFFFFF),
    roundedBg(color, radiusDp, strokeColor),
    null,
)

fun uiText(
    context: Context,
    sizeSp: Float,
    color: Int,
    weight: Int = Typeface.NORMAL,
    family: String = "sans-serif",
): TextView = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Typeface.create(family, weight)
}
