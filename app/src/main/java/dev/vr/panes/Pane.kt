package dev.vr.panes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import kotlin.math.roundToInt

/** Fills its bounds like centerCrop, but always trims the caption bar FDOT burns into the top of each frame. */
class Pane @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {
    init { scaleType = ScaleType.MATRIX }

    /** Only road frames carry the caption bar; scenic frames are shown whole ([Cam.trim]). */
    var trim = TRIM
        set(v) { field = v; fit() }

    /** The share of the view the picture fills ([window]), centered on [windowY] of its height; 1 is edge to edge. */
    var window = 1f
        set(v) { field = v; fit() }
    var windowY = .5f
        set(v) { field = v; fit() }
    private val glide = Glide { window = it }
    private val glideY = Glide { windowY = it }

    fun place(k: Float, cy: Float, animate: Boolean = true) { glide.to(window, k, animate); glideY.to(windowY, cy, animate) }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean = super.setFrame(l, t, r, b).also { fit() }

    override fun setImageDrawable(d: Drawable?) { super.setImageDrawable(d); fit() }

    private fun fit() {
        val d = drawable ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val bw = d.intrinsicWidth.toFloat(); val bh = d.intrinsicHeight.toFloat()
        if (w == 0f || bw <= 0f) return
        val r = rect(width, height, window, windowY)
        imageMatrix = fill(r.width().toFloat(), r.height().toFloat(), bw, bh, trim).apply { postTranslate(r.left.toFloat(), r.top.toFloat()) }
    }

    companion object {
        const val TRIM = 0.085f
        /** SD encoders pad and smear the outer pixels of a frame; trimmed frames lose this much on every other edge too. */
        const val EDGE = 0.012f
        /**
         * The caps behind the Picture size setting, Smaller then Full screen: screen pixels per source pixel at 1080 lines
         * a frame may reach before it sits in a window; 0 is no cap (edge to edge for everything).
         */
        val CAPS = floatArrayOf(1.2f, 0f)

        /** The magnification [fill] needs to cover a w×h view with a bw×bh frame. */
        fun scale(w: Float, h: Float, bw: Float, bh: Float, trim: Float = TRIM): Float {
            val e = if (trim > 0f) EDGE else 0f
            return maxOf(w / (bw * (1 - 2 * e)), h / (bh * (1 - trim - e)))
        }

        /** The window a share [k] of a w×h view makes, centered on [cy] of its height, in whole pixels so every layer agrees to the pixel. */
        fun rect(w: Int, h: Int, k: Float, cy: Float): Rect {
            val ww = (w * k).roundToInt(); val wh = (h * k).roundToInt()
            val x = (w - ww) / 2; val y = (h * cy - wh / 2f).roundToInt()
            return Rect(x, y, x + ww, y + wh)
        }

        /** The share of a w×h view a bw×bh frame may fill without passing [cap]: at 1.2, 1 for 1080p, .8 for 720p. */
        fun window(w: Float, h: Float, bw: Float, bh: Float, trim: Float, cap: Float): Float =
            if (cap <= 0f) 1f else minOf(1f, cap * h / 1080f / scale(w, h, bw, bh, trim))

        /**
         * Maps a bw×bh frame onto a w×h view: cover the view, centered, with at least the top [trim] of the frame cut off
         * (and [EDGE] off the other sides when trimming). Extra vertical overflow comes off evenly, which takes more of the caption bar.
         */
        fun fill(w: Float, h: Float, bw: Float, bh: Float, trim: Float = TRIM): Matrix {
            val e = if (trim > 0f) EDGE else 0f
            val s = scale(w, h, bw, bh, trim)
            // Not coerceIn: when the height binds, both bounds are equal and float error can invert them.
            val top = maxOf(bh * trim * s, minOf((bh * s - h) / 2, bh * s - h - bh * e * s))
            return Matrix().apply { setScale(s, s); postTranslate((w - bw * s) / 2, -top) }
        }
    }
}

/**
 * The backdrop behind a windowed picture: the same frame, blurred and dimmed, filling the screen, with a rounded
 * hole where the sharp picture shows through. Drawn into its own layer so the hole can be cleared, not clipped (clipping isn't antialiased).
 */
class Matte @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var window = 1f
        set(v) { field = v; invalidate() }
    var windowY = .5f
        set(v) { field = v; invalidate() }
    private val glide = Glide { window = it }
    private val glideY = Glide { windowY = it }
    private var old: Bitmap? = null
    private var now: Bitmap? = null
    /** How far the new backdrop has dissolved over the old one. */
    private var mix = 1f
    private val dissolve = Glide { mix = it; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val bounds = RectF()
    private val hole = RectF()
    private val radius = context.dp(12).toFloat()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun place(k: Float, cy: Float, animate: Boolean = true) { glide.to(window, k, animate); glideY.to(windowY, cy, animate) }

    /** A new backdrop dissolves in over the last; while the matte is hidden it just lands. */
    fun show(b: Bitmap?) {
        old = now; now = b
        dissolve.to(0f, 1f, animate = alpha > 0f && old != null)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bounds.set(0f, 0f, w, h)
        c.drawColor(context.getColor(R.color.ink))
        if (mix < 1f) old?.let { paint.alpha = 255; c.drawBitmap(it, null, bounds, paint) }
        now?.let { paint.alpha = (mix * 255).toInt(); c.drawBitmap(it, null, bounds, paint) }
        // Dim enough that the window reads as the subject and the clock and title stay legible on a bright frame.
        c.drawColor(0x9E000000.toInt())
        hole.set(Pane.rect(width, height, window, windowY))
        c.drawRoundRect(hole, radius, radius, clear)
    }
}
