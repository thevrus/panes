package dev.vr.panes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** The tvOS activity indicator: eight spokes, the lit one stepping round with a fading tail. Shown while a stream connects. */
class Spinner @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = ctx.getColor(R.color.text)
    }
    private var head = 0
    private val spin = ValueAnimator.ofInt(0, SPOKES).apply {
        duration = 800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { val h = (it.animatedValue as Int) % SPOKES; if (h != head) { head = h; invalidate() } }
    }

    override fun onVisibilityChanged(v: View, visibility: Int) {
        if (visibility == VISIBLE && isShown) spin.start() else spin.cancel()
    }

    override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f
        paint.strokeWidth = r * .24f
        val outer = r - paint.strokeWidth / 2
        c.save(); c.translate(width / 2f, height / 2f)
        for (i in 0 until SPOKES) {
            // Brightest at the head, dimming behind it to a floor that keeps the whole ring legible.
            val age = (head - i + SPOKES) % SPOKES
            paint.alpha = (255 * maxOf(.25f, 1f - age * .14f)).toInt()
            c.drawLine(0f, -outer * .48f, 0f, -outer, paint)
            c.rotate(360f / SPOKES)
        }
        c.restore()
    }

    private companion object { const val SPOKES = 8 }
}
