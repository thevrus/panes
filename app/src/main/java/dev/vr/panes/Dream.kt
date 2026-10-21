package dev.vr.panes

import android.view.View
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Screensaver: favorites (or the nearest cameras) one at a time, each held for 20 seconds. */
class Dream : DreamService() {
    private val ui = Handler(Looper.getMainLooper())
    private var cams = emptyList<Cam>()
    private var at = -1
    private var on = false
    private lateinit var a: Pane
    private lateinit var b: Pane
    private lateinit var caption: TextView
    private lateinit var time: TextView
    private lateinit var date: TextView
    private var place: Place? = null
    private var weather: Weather.Now? = null
    /** Turns over on the minute, in the cameras' own time zone once the weather has said which it is. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            time.text = Clock.time(this@Dream, weather?.zone, now); date.text = Clock.line(place?.name, weather, now)
            ui.postDelayed(this, 60_000 - now % 60_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = false
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.ink)) }
        a = Pane(this)
        b = Pane(this).apply { alpha = 0f }
        root.addView(a, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(b, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The app's own ambient shade: pools under the name and the clock, the picture clear between them.
        root.addView(View(this).apply { background = shade() }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        caption = TextView(this).apply {
            textSize = 20f; setTextColor(getColor(R.color.text))
            typeface = resources.getFont(R.font.geist_semibold)
            onPicture()
        }
        // The time sits where the app keeps it, top right, with the day and the weather under it, quiet as Aerial's.
        time = TextView(this).apply {
            textSize = 40f; setTextColor(getColor(R.color.text)); letterSpacing = -.02f; includeFontPadding = false; fontFeatureSettings = "tnum"
            typeface = resources.getFont(R.font.geist_semibold)
            onPicture()
        }
        date = TextView(this).apply {
            textSize = 16f; setTextColor(getColor(R.color.on_picture_dim))
            typeface = resources.getFont(R.font.geist_medium)
            onPicture()
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(time); addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(48); topMargin = dp(40)
        })
        root.addView(caption, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(48); bottomMargin = dp(40)
        })
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        on = true
        tick.run()
        val repo = Repo(this)
        Net.pool.execute {
            val p = runCatching { repo.place() }.getOrNull()
            val w = p?.let { runCatching { Weather.fetch(it.lat, it.lon) }.getOrNull() }
            ui.post { if (on) { place = p; weather = w; ui.removeCallbacks(tick); tick.run() } }
            val list = p?.let { runCatching { repo.cameras(it, limit = 12) }.getOrNull() }.orEmpty()
            val favs = repo.favorites
            ui.post { if (!on) return@post; cams = list.filter { it.id in favs }.ifEmpty { list }; next() }
        }
    }

    // Work already on the pool posts back after a stop; [on] keeps it from restarting the loop.
    override fun onDreamingStopped() { on = false; ui.removeCallbacksAndMessages(null); super.onDreamingStopped() }

    private fun next(tries: Int = 0) {
        if (cams.isEmpty() || tries > cams.size) { ui.postDelayed({ next() }, 60_000); return }
        at = (at + 1) % cams.size
        val cam = cams[at]
        Net.pool.execute {
            val f = Snap.fetch(cam, resources.displayMetrics.widthPixels)
            ui.post {
                if (!on) return@post
                if (f == null) { next(tries + 1); return@post }
                val (inc, out) = if (a.alpha > b.alpha) b to a else a to b
                inc.trim = cam.trim
                inc.setImageBitmap(f.bitmap); inc.scaleX = 1f; inc.scaleY = 1f
                inc.fadeTo(1f, 1_500).start(); drift(inc, 1.08f, 24_000)
                out.fadeTo(0f, 1_500).start()
                // The name crossfades with the picture instead of snapping to the next one.
                caption.fadeTo(0f).withEndAction { caption.text = cam.title; caption.fadeTo(1f).start() }.start()
                ui.postDelayed({ next() }, 20_000)
            }
        }
    }
}
