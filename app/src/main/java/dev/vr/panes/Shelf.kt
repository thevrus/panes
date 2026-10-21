package dev.vr.panes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView

/** A shelf that lets the activity decide scrolling, so focus glides to a fixed left edge like tvOS. */
class Shelf(ctx: Context, attrs: AttributeSet?) : HorizontalScrollView(ctx, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
}

/** The vertical twin of [Shelf], for the grid and the location list. */
class Wall(ctx: Context, attrs: AttributeSet?) : ScrollView(ctx, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** The one ease every chrome motion shares, so nothing on screen moves at its own pace. */
val ease = DecelerateInterpolator(2f)

fun View.fadeTo(a: Float, d: Long = 380L) = animate().alpha(a).setDuration(d).setInterpolator(ease)

/** Eases one float (a window fraction, a dissolve) at the chrome pace; a new target takes over from wherever the last one got. */
class Glide(private val set: (Float) -> Unit) {
    private var anim: ValueAnimator? = null
    fun to(from: Float, v: Float, animate: Boolean) {
        anim?.cancel()
        if (!animate || from == v) { set(v); return }
        anim = ValueAnimator.ofFloat(from, v).apply {
            duration = 380; interpolator = ease
            addUpdateListener { set(it.animatedValue as Float) }
            start()
        }
    }
}

/** A very slow push-in so a still frame feels like a window, not a photo. */
fun drift(v: View, scale: Float = 1.07f, ms: Long = 40_000) {
    v.animate().scaleX(scale).scaleY(scale).setDuration(ms).setInterpolator(LinearInterpolator()).start()
}

/** The OnPicture shadow for text built in code (the style's radius is in px; this one scales with density). */
fun TextView.onPicture() {
    val den = resources.displayMetrics.density
    setShadowLayer(10 * den, 0f, den, 0x73000000)
}

/** Three box-blur passes approximate a gaussian; cheap on a thumbnail-sized frame. Drops alpha. */
fun Bitmap.blurred(r: Int): Bitmap {
    val px = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    val tmp = IntArray(px.size)
    repeat(3) { boxPass(px, tmp, width, height, r, true); boxPass(tmp, px, width, height, r, false) }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}

/** A shelf thumbnail made fit to stand in full screen while the real frame loads: soft enough that no pixels show. */
fun Bitmap.soft(): Bitmap = Bitmap.createScaledBitmap(this, 160, maxOf(1, 160 * height / width), true).blurred(2)

/** A frame shrunk and blurred into a backdrop, its caption bar ([trim]) left out. Upscaling a blur this soft shows no pixels. */
fun Bitmap.haze(trim: Float = 0f): Bitmap {
    val small = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
    Canvas(small).drawBitmap(this, Rect(0, (height * trim).toInt(), width, height), Rect(0, 0, 160, 90), Paint(Paint.FILTER_BITMAP_FLAG))
    return small.blurred(6)
}

private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val n = 2 * r + 1
    for (line in 0 until if (horizontal) h else w) {
        fun at(i: Int) = i.coerceIn(0, len - 1).let { if (horizontal) line * w + it else it * w + line }
        var red = 0; var green = 0; var blue = 0
        for (i in -r..r) { val p = src[at(i)]; red += p shr 16 and 255; green += p shr 8 and 255; blue += p and 255 }
        for (i in 0 until len) {
            dst[at(i)] = (0xFF shl 24) or (red / n shl 16) or (green / n shl 8) or (blue / n)
            val add = src[at(i + r + 1)]; val sub = src[at(i - r)]
            red += (add shr 16 and 255) - (sub shr 16 and 255)
            green += (add shr 8 and 255) - (sub shr 8 and 255)
            blue += (add and 255) - (sub and 255)
        }
    }
}

/** Black fade (top to bottom, or left to right) with eased stops and dithering, so it never bands into a visible edge. */
fun fade(vararg stops: Pair<Float, Float>, across: Boolean = false): PaintDrawable = PaintDrawable().apply {
    shape = RectShape(); paint.isDither = true
    shaderFactory = object : ShapeDrawable.ShaderFactory() {
        override fun resize(w: Int, h: Int) = LinearGradient(0f, 0f, if (across) w.toFloat() else 0f, if (across) 0f else h.toFloat(),
            IntArray(stops.size) { (stops[it].second * 255).toInt() shl 24 },
            FloatArray(stops.size) { stops[it].first }, Shader.TileMode.CLAMP)
    }
}

/**
 * A pool of shade spreading from a corner ([cx], [cy] as fractions of the screen) across an ellipse of [rx] by [ry]
 * screen widths and heights, eased out to nothing. Shade sits under the text, not over the whole sky.
 */
fun pool(cx: Float, cy: Float, rx: Float, ry: Float, vararg stops: Pair<Float, Float>): PaintDrawable = PaintDrawable().apply {
    shape = RectShape(); paint.isDither = true
    shaderFactory = object : ShapeDrawable.ShaderFactory() {
        override fun resize(w: Int, h: Int) = RadialGradient(0f, 0f, 1f,
            IntArray(stops.size) { (stops[it].second * 255).toInt() shl 24 },
            FloatArray(stops.size) { stops[it].first }, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx * w, ry * h); postTranslate(cx * w, cy * h) })
        }
    }
}

/**
 * The ambient shade, Aerial-style: the picture stays clear, a low band under the shelf, a deeper pool where the
 * title sits (bottom left) and a smaller one under the clock (top right). Everything else on top carries glass.
 */
fun shade(): Drawable = LayerDrawable(arrayOf(
    fade(0f to 0f, .5f to 0f, .62f to .06f, .74f to .2f, .86f to .4f, .94f to .52f, 1f to .58f),
    pool(0f, 1f, .78f, .62f, 0f to .62f, .3f to .5f, .55f to .28f, .78f to .09f, 1f to 0f),
    pool(1f, 0f, .4f, .42f, 0f to .5f, .35f to .36f, .65f to .14f, .85f to .04f, 1f to 0f),
))
