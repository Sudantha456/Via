package com.via.bgplay.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.via.bgplay.R

/**
 * Small, dependency free switch drawn by hand so it matches the player theme and
 * works on every API level from 21.
 */
class PillSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
            contentDescription = if (value) "On" else "Off"
            listener?.invoke(value)
        }

    var listener: ((Boolean) -> Unit)? = null

    private var fraction = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    private val onColor = ContextCompat.getColor(context, R.color.bgp_accent)
    private val offColor = ContextCompat.getColor(context, R.color.bgp_divider)
    private val thumbColor = ContextCompat.getColor(context, R.color.bgp_text_primary)

    init {
        isClickable = true
        isFocusable = true
    }

    fun setCheckedSilently(value: Boolean) {
        if (isChecked == value) return
        isChecked = value
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)

        trackPaint.color = blend(offColor, onColor, fraction)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val thumbRadius = radius - dp(3f)
        val startX = radius
        val endX = w - radius
        val cx = startX + (endX - startX) * fraction
        thumbPaint.color = thumbColor
        canvas.drawCircle(cx, h / 2f, thumbRadius, thumbPaint)
    }

    override fun performClick(): Boolean {
        isChecked = !isChecked
        return super.performClick()
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * ratio).toInt()
        val g = (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * ratio).toInt()
        val b = (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * ratio).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
