package dev.vr.panes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/**
 * tvOS Liquid Glass, as far as API 28 can draw it: the picture behind, blurred and richer, toned down until white text
 * reads on it, a faint white body, light caught along the top lip, and a rim felt more than seen. No resting shadow,
 * so nothing smears below it. There is no live backdrop blur before API 31, so it samples [source]: the frame on
 * screen, already shrunk and blurred to 160x90 and stretched over the whole screen, as the matte draws it.
 */
class Glass(private val host: View, private val radius: Float, private val source: () -> Bitmap?) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val at = IntArray(2)
    private val local = Matrix()
    private val tone = ColorMatrix()
    private val scale = ColorMatrix()
    private var shaderOf: Bitmap? = null
    private var toneKey = 0L
    private var alpha = 255
    private var shadedH = -1f
    private var shadedA = -1

    override fun draw(c: Canvas) {
        if (bounds.isEmpty) return
        box.set(bounds)
        val r = minOf(radius, box.height() / 2)
        val b = source()
        if (b != null) {
            if (b !== shaderOf) { shaderOf = b; body.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); toneKey = 0 }
            // Where this view sits on screen picks the part of the frame it lets through.
            host.getLocationOnScreen(at)
            val dm = host.resources.displayMetrics
            local.setScale(dm.widthPixels / b.width.toFloat(), dm.heightPixels / b.height.toFloat())
            local.postTranslate(-at[0].toFloat(), -at[1].toFloat())
            body.shader.setLocalMatrix(local)
            tone(b, at[0] * b.width / dm.widthPixels, at[1] * b.height / dm.heightPixels,
                maxOf(1, (box.width() * b.width / dm.widthPixels).toInt()), maxOf(1, (box.height() * b.height / dm.heightPixels).toInt()))
            body.alpha = alpha
            c.drawRoundRect(box, r, r, body)
        }
        // Without a frame yet it's frost alone: a little more white so the shape still reads.
        tint.color = (if (b != null) 0x18 else 0x24) * alpha / 255 shl 24 or 0xFFFFFF
        c.drawRoundRect(box, r, r, tint)
        // Gradients only change with the shape's height or the fade, not on every frame of a lift.
        val w = host.resources.displayMetrics.density
        if (box.height() != shadedH || alpha != shadedA) {
            shadedH = box.height(); shadedA = alpha
            // The top lip catches the light and lets it go before the middle.
            lip.shader = LinearGradient(0f, 0f, 0f, box.height() * .55f,
                (0x2E * alpha / 255) shl 24 or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            // The rim: bright where the light lands on top, nearly gone along the bottom.
            rim.strokeWidth = w
            rim.shader = LinearGradient(0f, 0f, 0f, box.height(),
                intArrayOf((0x47 * alpha / 255) shl 24 or 0xFFFFFF, (0x0F * alpha / 255) shl 24 or 0xFFFFFF, (0x1A * alpha / 255) shl 24 or 0xFFFFFF),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        }
        // Both gradients are drawn from the shape's own top.
        c.save(); c.translate(0f, box.top); box.offset(0f, -box.top)
        c.drawRoundRect(box, r, r, lip)
        // Inset half a stroke, so the rim never spills past the shape.
        box.inset(w / 2, w / 2)
        c.drawRoundRect(box, r - w / 2, r - w / 2, rim)
        c.restore()
    }

    /**
     * Glass over a bright sky would leave white text on pale grey: darken the sample until its own patch averages
     * about 28% light, keeping the colour only just richer, so a warm scene tints the glass without turning it brown.
     */
    private fun tone(b: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val l = x.coerceIn(0, b.width - 1); val t = y.coerceIn(0, b.height - 1)
        val rw = w.coerceAtMost(b.width - l); val rh = h.coerceAtMost(b.height - t)
        val key = (l.toLong() shl 48) or (t.toLong() shl 32) or (rw.toLong() shl 16) or rh.toLong()
        if (key == toneKey) return
        toneKey = key
        val px = IntArray(rw * rh).also { b.getPixels(it, 0, rw, l, t, rw, rh) }
        val mean = px.sumOf { (it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114 } / (px.size * 255_000.0)
        val k = minOf(1.0, .28 / maxOf(mean, .01)).toFloat()
        tone.setSaturation(1.05f); scale.setScale(k, k, k, 1f); tone.postConcat(scale)
        body.colorFilter = ColorMatrixColorFilter(tone)
    }

    override fun getOutline(o: Outline) { o.setRoundRect(bounds, minOf(radius, bounds.height() / 2f)) }
    override fun setAlpha(a: Int) { if (a != alpha) { alpha = a; invalidateSelf() } }
    override fun getAlpha() = alpha
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A control the tvOS way: glass at rest, the bright platter under focus. The two dissolve into each other rather than
 * swap, so focus never pops. [radius] caps at half the height, so the default is a capsule.
 */
fun View.glassy(source: () -> Bitmap?, radius: Float = 999f) {
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = radius; setColor(context.getColor(R.color.platter))
        })
        addState(intArrayOf(), Glass(this@glassy, radius, source))
        setEnterFadeDuration(160); setExitFadeDuration(160)
    }
}
