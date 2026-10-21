package dev.vr.panes

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * YouTube live through the official IFrame player, the only sanctioned way to show YouTube on Android.
 * Muted, no controls, never focusable: the remote keeps driving the app, not the page.
 * YouTube's rules forbid drawing over the player, so callers hide their chrome while it is live.
 * The player stays behind the caller's poster ([onStall]) until it has played steadily for 5 s, so YouTube's
 * title, logo and "More videos" never show. That is muted hidden playback, which YouTube's developer policies
 * (III.I.9) forbid: fine on our own TVs, must change to an immediate reveal before the app is published.
 */
class Tube(private val web: WebView, private val onLive: () -> Unit, private val onLost: (dead: Boolean) -> Unit,
           private val onStall: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var playing: String? = null
    // The page and player stay loaded between cameras, so a switch is one loadVideoById, not a fresh embed.
    private var ready = false
    // Counts page loads, so a late ready() from a page already replaced can't mark the new one ready.
    private var page = 0
    // A stopped player stays warm for a minute, then gives back the renderer's memory.
    private val cool = Runnable { page++; ready = false; web.loadUrl("about:blank") }
    var live = false; private set
    private val reveal: Runnable = Runnable { live = true; web.fadeTo(1f).start(); onLive() }
    private val hide: Runnable = Runnable { if (live) { live = false; web.fadeTo(0f, 160).start(); web.evaluateJavascript("snd(0,5)", null); onStall() } }
    /** Whether the player may be heard: only once revealed, so nothing ever sounds from behind the poster. */
    private var sound = false

    /** Hold the poster over the player until YouTube's title and logo have faded. Off outside personal builds. */
    var holdPoster = false

    fun sound(on: Boolean) {
        if (on == sound) return
        sound = on
        if (live) web.evaluateJavascript("snd(${if (on) 1 else 0})", null)
    }
    // Live embeds on the SHIELD rebuffer for a second every few seconds, so play time adds up across blips.
    private var played = 0L
    private var since = 0L

    init {
        @SuppressLint("SetJavaScriptEnabled")
        web.settings.run { javaScriptEnabled = true; mediaPlaybackRequiresUserGesture = false; domStorageEnabled = true }
        web.isFocusable = false; web.isFocusableInTouchMode = false
        web.setBackgroundColor(0)
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Panes")
        // Rounded by the view itself, so the matte's rounded hole never covers a corner of the player.
        web.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, if (windowed) v.context.dp(12).toFloat() else 0f) }
        }
        web.clipToOutline = true
    }

    private var windowed = false

    /** Sizes the player to the picture window: YouTube forbids drawing over it, so the window is the player, not a hole in front of it. */
    fun place(r: Rect) {
        val lp = web.layoutParams as FrameLayout.LayoutParams
        // The box lays out again after this, so only touch the params when the window moved.
        if (lp.width == r.width() && lp.height == r.height() && lp.leftMargin == r.left && lp.topMargin == r.top) return
        lp.width = r.width(); lp.height = r.height(); lp.leftMargin = r.left; lp.topMargin = r.top
        windowed = r.width() < (web.parent as View).width
        web.layoutParams = lp; web.invalidateOutline()
    }

    fun play(videoId: String) {
        if (videoId == playing) return
        ui.removeCallbacks(cool)
        playing = videoId; live = false; played = 0; since = 0
        web.visibility = View.VISIBLE; web.alpha = 0f
        // A channel can't be cued into a loaded player, so it always gets a fresh embed.
        if (ready && !channel(videoId)) { web.evaluateJavascript("go('$videoId')", null); return }
        page++; ready = false
        // An https origin gives the embed the Referer YouTube requires (error 153 without one).
        web.loadDataWithBaseURL(origin, html(videoId, page), "text/html", "utf-8", null)
    }

    fun stop() {
        if (playing == null) return
        ui.removeCallbacks(reveal); ui.removeCallbacks(hide)
        playing = null; live = false
        if (ready) { web.evaluateJavascript("p.stopVideo()", null); ui.postDelayed(cool, 60_000) } else cool.run()
        web.visibility = View.GONE
    }

    /** Frees the renderer now instead of whenever GC finds the bridge, which holds the activity's callbacks. */
    fun release() {
        stop(); ui.removeCallbacksAndMessages(null); web.removeJavascriptInterface("Panes")
        (web.parent as? ViewGroup)?.removeView(web); web.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface fun ready(p: Int) = ui.post { if (p == page) ready = true }.let { }
        // YT.PlayerState: 1 playing, 2 paused, 3 buffering, 0 ended, -1 unstarted, 5 cued.
        private var lastQuality = ""
        /** Every 30 s while playing: the player only reports quality on state changes, so a mid-stream downshift would go unseen. */
        @JavascriptInterface fun quality(q: String, id: String) = ui.post {
            if (id == playing && q != lastQuality) { lastQuality = q; Log.i(TAG, "quality $playing $q") }
        }.let { }
        @JavascriptInterface fun state(s: Int, id: String, quality: String) = ui.post {
            if (id != playing) return@post // A late event from the page being replaced.
            Log.i(TAG, "state $playing $s $quality")
            val now = System.currentTimeMillis()
            when (s) {
                1 -> {
                    ui.removeCallbacks(hide); since = now
                    if (!live) { ui.removeCallbacks(reveal); ui.postDelayed(reveal, if (holdPoster) maxOf(0L, 5_000 - played) else 0L) }
                }
                // A channel's stream ending is its daily restart, not a dead camera: the next play opens the new one.
                0 -> { ui.removeCallbacks(reveal); ui.removeCallbacks(hide); live = false; onLost(!channel(id)) }
                else -> {
                    ui.removeCallbacks(reveal)
                    if (since > 0) { played += now - since; since = 0 }
                    // A short rebuffer keeps the picture up; only a real stall brings the poster back.
                    if (live) { ui.removeCallbacks(hide); if (s == 3) ui.postDelayed(hide, 3_000) else hide.run() }
                }
            }
        }.let { }
        // 100 removed or private, 101/150 embedding refused: the video itself is gone. Anything else (2, 5, network) may pass.
        @JavascriptInterface fun error(code: Int, id: String) = ui.post {
            if (id != playing) return@post
            Log.w(TAG, "error $playing $code")
            ui.removeCallbacks(reveal); live = false; onLost(code in DEAD && !channel(id))
        }.let { }
    }

    // The iframe is in the markup so the embed starts loading alongside iframe_api instead of after it.
    private fun html(id: String, n: Int) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
        </head><body><iframe id="p" allow="autoplay" src="https://www.youtube.com/embed/${if (channel(id)) "live_stream?channel=$id&" else "$id?"}enablejsapi=1&origin=${origin}&autoplay=1&mute=1&controls=0&disablekb=1&fs=0&iv_load_policy=3&playsinline=1&rel=0"></iframe>
        <script>
        var p,cur='$id',ch=${channel(id)};
        function go(id){cur=id;ch=false;p.loadVideoById(id);p.mute()}
        // A dozen steps over about 380 ms, so sound comes and goes with the picture instead of popping.
        var vt=null;function snd(on,n){n=n||12;clearInterval(vt);if(on)p.unMute();var v=on?0:p.getVolume(),i=0;p.setVolume(v);
          vt=setInterval(function(){i++;p.setVolume(Math.round(on?v+(100-v)*i/n:v*(n-i)/n));if(i>=n){clearInterval(vt);if(!on)p.mute()}},32)}
        setInterval(function(){if(p&&p.getPlayerState&&p.getPlayerState()==1)Panes.quality(p.getPlaybackQuality()||'',vid(p))},30000);
        // The player's own video id, so a late event from the previous video isn't blamed on the new one.
        // A channel embed reports its current stream's id, so it answers to the channel id it was opened with.
        function vid(t){if(ch)return cur;var m=/[?&]v=([^&#]+)/.exec(t.getVideoUrl()||'');return m?m[1]:cur}
        function onYouTubeIframeAPIReady(){p=new YT.Player('p',{
          events:{onReady:function(e){e.target.mute();e.target.playVideo();Panes.ready($n)},
                  onStateChange:function(e){Panes.state(e.data,vid(e.target),e.target.getPlaybackQuality()||'')},onError:function(e){Panes.error(e.data,vid(e.target))}}})}
        </script>
        <script src="https://www.youtube.com/iframe_api"></script></body></html>
    """.trimIndent()

    private fun channel(id: String) = id.length == 24 && id.startsWith("UC")

    private val origin get() = "https://${web.context.packageName}"

    companion object {
        private const val TAG = "PanesTube"
        private val DEAD = setOf(100, 101, 150)
    }
}
