package dev.vr.panes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One live player, silent unless the viewer turns Sound on, bound to a SurfaceView, so the compositor scales frames at the panel's resolution
 * (a TextureView goes through the UI layer, which on a 4K TV usually renders at 1080p). FL511 streams are token-gated HLS:
 * GetVideoUrl hands out a source token, DIVAS swaps it for a query-string token,
 * and the stream server only answers requests that carry fl511.com as Referer.
 */
@OptIn(UnstableApi::class)
class Video(ctx: Context, private val surface: SurfaceView, web: WebView, private val listener: Listener) {
    interface Listener {
        fun onLive(cam: Cam)
        /** [dead]: the source refused or ended it; otherwise it only timed out and may be fine later. */
        fun onLost(cam: Cam, dead: Boolean)
        fun onStall(cam: Cam)
        /** The frame as displayed: [width] already corrected for non-square pixels. */
        fun onSize(cam: Cam, width: Int, height: Int)
        /** Whether the stream carries sound at all (road cams mostly don't); YouTube is taken to. */
        fun onAudio(cam: Cam, has: Boolean)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setUserAgent("Panes/1.0 (Android TV)")
                .setDefaultRequestProperties(mapOf("Referer" to "https://fl511.com/", "Origin" to "https://fl511.com"))
                .setConnectTimeoutMs(5_000).setReadTimeoutMs(8_000)))
        // Live traffic feeds hiccup; hold a deeper buffer than the default so a stall rarely reaches the screen,
        // but start on the default 1 s like any player would.
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 40_000, 1_000, 3_000).build())
        // A TV sits on home broadband. The stock guess (about 2.4 Mbps on US Wi-Fi) opens every camera on a soft
        // low variant and climbs from there; guessing high opens on the sharpest one the display can show.
        .setBandwidthMeter(DefaultBandwidthMeter.Builder(ctx).setInitialBitrateEstimate(20_000_000).build())
        .build().apply { volume = 0f; setVideoSurfaceView(surface) }

    private var cam: Cam? = null
    private var started = false
    private var request = 0
    /** The part of the frame on screen, in video pixels. */
    private var shown: Rect? = null
    private val box get() = surface.parent as View
    private val tooSlow = Runnable { cam?.let { lost(it, dead = false) } }
    // fading clears last: ExoPlayer reports the stop synchronously, and those events belong to the old stream too.
    private val halt = Runnable { player.stop(); player.clearMediaItems(); fading = false }
    /** The last stream is fading its sound out after stop(): its events belong to no camera, so they are dropped. */
    private var fading = false
    /** The playing stream has an audio track. */
    private var audio = false
    private val gain = Glide { player.volume = it }
    private val focus = ctx.getSystemService(AudioManager::class.java)
    private var focused = false
    private var focusRequestOrNull: AudioFocusRequest? = null
    /** Another app speaks briefly over us (navigation, an assistant): quieter, not silent. */
    private var duck = false
    /** Another app has the audio focus: stay quiet, but keep the viewer's choice. */
    private var lost = false
    /** Another app took the focus for good: don't take it straight back; the next camera or a sound toggle may ask again. */
    private var yielded = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({
            Log.i(TAG, "focus $it")
            // A permanent loss gives focus back; the viewer's choice stays, and the next camera asks again.
            if (it == AudioManager.AUDIOFOCUS_LOSS) { focus.abandonAudioFocusRequest(focusRequestOrNull ?: return@setOnAudioFocusChangeListener); focused = false; yielded = true }
            duck = it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            lost = it != AudioManager.AUDIOFOCUS_GAIN && !duck; hear()
        }, ui).build().also { focusRequestOrNull = it }

    /** The viewer wants sound. Takes effect only while a stream with audio plays, and never from behind a poster. */
    var sound = false
        set(v) { field = v; yielded = false; hear() }
    private val tube = Tube(web,
        onLive = { cam?.let { started = true; ui.removeCallbacks(tooSlow); listener.onLive(it); hear() } },
        onLost = { dead -> cam?.let { lost(it, dead) } },
        // A stall puts the poster back and gives the stream the usual grace before falling back to stills.
        onStall = { cam?.let { started = false; ui.removeCallbacks(tooSlow); ui.postDelayed(tooSlow, 20_000); listener.onStall(it); hear() } })

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (fading) return
                started = true; ui.removeCallbacks(tooSlow); cam?.let(listener::onLive); hear()
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (fading) return
                audio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                Log.i(TAG, "tracks ${cam?.id} audio=$audio")
                cam?.let { listener.onAudio(it, audio) }; hear()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (fading) return
                // A stalled stream reads as "laggy": give it 8 s, then fall back to the still frame.
                if (state == Player.STATE_BUFFERING) { ui.removeCallbacks(tooSlow); ui.postDelayed(tooSlow, 8_000) }
                if (state == Player.STATE_READY) ui.removeCallbacks(tooSlow)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (fading) return
                Log.w(TAG, "player error ${cam?.id}: ${error.errorCodeName} ${error.cause}")
                cam?.let { lost(it) }
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (fading) return
                Log.i(TAG, "size ${cam?.id} ${size.width}x${size.height} par=${size.pixelWidthHeightRatio}")
                crop(size)
                if (size.height > 0) cam?.let { listener.onSize(it, (size.width * par(size)).roundToInt(), size.height) }
            }
        })
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refit() }
    }

    private var window = 1f
    private var windowY = .5f

    /**
     * The share of the box the frame fills, centered on [cy] of its height, as [Pane.place]. The overflow of a smaller
     * window hides under the matte; the YouTube player is sized to the window itself, so nothing ever draws over it.
     */
    fun place(k: Float, cy: Float) {
        if (window == k && windowY == cy) return
        window = k; windowY = cy; refit()
    }

    private fun refit() {
        crop(player.videoSize)
        if (box.width > 0) tube.place(Pane.rect(box.width, box.height, window, windowY))
    }

    val playing get() = started
    val connecting get() = cam != null && !started
    fun showing(c: Cam) = cam == c
    /** YouTube is playing: nothing may be drawn over it, and its frames can't be read back. */
    val youtube get() = cam?.tube?.isNotEmpty() == true

    fun play(c: Cam) {
        if (c == cam && (started || player.isLoading || c.tube.isNotEmpty())) { listener.onAudio(c, audio); return }
        stop(); yielded = false
        Log.i(TAG, "play ${c.id} stream=${c.video.ifEmpty { c.tube.ifEmpty { "none" } }} auth=${c.videoAuth}")
        if (c.tube.isNotEmpty()) { cam = c; audio = true; listener.onAudio(c, true); ui.postDelayed(tooSlow, 20_000); tube.play(c.tube); return }
        if (c.video.isEmpty()) return
        cam = c; audio = false
        val req = ++request
        ui.postDelayed(tooSlow, 12_000)
        Net.async({ resolve(c) ?: "" }) { url ->
            Log.i(TAG, "resolve ${c.id} -> ${url.ifEmpty { "FAILED" }.take(90)}")
            if (req != request) return@async
            if (url.isEmpty()) { lost(c); return@async }
            // A stream still fading out gives way now.
            ui.removeCallbacks(halt); halt.run()
            surface.visibility = View.VISIBLE
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare(); player.play()
        }
    }

    fun stop() {
        request++; started = false; cam = null; audio = false
        // The last frame would otherwise sit on the surface and snap to the next camera's window.
        surface.visibility = View.INVISIBLE
        ui.removeCallbacks(tooSlow); ui.removeCallbacks(halt)
        tube.stop()
        hear()
        // A stream that can be heard fades out with the picture's crossfade rather than cutting off.
        if (player.volume > 0f) { fading = true; ui.postDelayed(halt, 400) } else halt.run()
    }

    /**
     * Sound on only when wanted, present, playing and ours to play (audio focus); eased over the chrome's 380 ms.
     * Focus is held only while sound is actually on, so a muted camera never keeps another app quiet.
     */
    private fun hear() {
        val want = sound && audio && started
        // The WebView takes audio focus for YouTube itself (and would knock ours straight out); only ExoPlayer needs ours.
        val own = want && !youtube
        if (own && !focused && !yielded) { focused = focus.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; lost = !focused }
        if (!own && focused) { focus.abandonAudioFocusRequest(focusRequest); focused = false; lost = false }
        val on = want && (youtube || (focused && !lost))
        Log.i(TAG, "sound ${cam?.id} on=$on (want=$sound audio=$audio started=$started focus=$focused lost=$lost)")
        tube.sound(on)
        gain.to(player.volume, if (!on) 0f else if (duck) .2f else 1f, animate = true)
    }

    /**
     * A [w]×[h] copy of what's on screen of the live frame, or null for YouTube and before the first frame.
     * Async: a SurfaceView's pixels live in the compositor, not in the view.
     */
    fun grab(w: Int, h: Int, done: (Bitmap?) -> Unit) {
        val r = shown
        if (!started || youtube || r == null || r.isEmpty || !surface.holder.surface.isValid) { done(null); return }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, r, b, { res -> done(if (res == PixelCopy.SUCCESS) b else { b.recycle(); null }) }, ui)
    }

    fun release() { stop(); ui.removeCallbacks(halt); halt.run(); player.release(); tube.release() }

    private fun lost(c: Cam, dead: Boolean = true) {
        if (c != cam) return
        stop(); listener.onLost(c, dead)
    }

    /**
     * Same framing as Pane: fill the screen and trim FDOT's burned-in caption bar.
     * SD streams often use non-square pixels (704×480 is 4:3 or 16:9 depending on the encoder), so frame at display width.
     */
    private fun par(size: VideoSize) = size.pixelWidthHeightRatio.let { if (it > 0f && it.isFinite()) it else 1f }

    private fun crop(size: VideoSize) {
        val vw = size.width; val vh = size.height; val par = par(size)
        if (vw <= 0 || vh <= 0 || box.width == 0) return
        // The window, in the same whole pixels as the matte's hole: the frame is framed inside it as Pane does.
        val win = Pane.rect(box.width, box.height, window, windowY)
        val w = win.width().toFloat(); val h = win.height().toFloat(); val ox = win.left.toFloat(); val oy = win.top.toFloat()
        val m = FloatArray(9).also { Pane.fill(w, h, vw * par, vh.toFloat(), cam?.trim ?: Pane.TRIM).getValues(it) }
        val s = m[Matrix.MSCALE_X]; val x = m[Matrix.MTRANS_X] + ox; val y = m[Matrix.MTRANS_Y] + oy
        shown = Rect(((ox - x) / (s * par)).roundToInt(), ((oy - y) / s).roundToInt(), ((ox + w - x) / (s * par)).roundToInt(), ((oy + h - y) / s).roundToInt())
            .apply { if (!intersect(0, 0, vw, vh)) setEmpty() }
        // A SurfaceView takes no transform: size it to the scaled frame and let the screen edge (or the matte) trim the overflow.
        // The buffer is pinned to the video's size, so grab()'s rect is in video pixels.
        surface.holder.setFixedSize(vw, vh)
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        // Floor the origin and ceil the size, so rounding never leaves a hairline of background at an edge.
        val nx = floor(x).toInt(); val ny = floor(y).toInt()
        val nw = ceil(x + vw * par * s).toInt() - nx; val nh = ceil(y + vh * s).toInt() - ny
        // The box re-lays out after this change too; only resize when something moved.
        if (lp.width == nw && lp.height == nh && lp.leftMargin == nx && lp.topMargin == ny) return
        lp.width = nw; lp.height = nh; lp.leftMargin = nx; lp.topMargin = ny
        Log.i(TAG, "crop ${cam?.id} ${vw}x$vh trim=${cam?.trim} k=$window cy=$windowY -> $nx,$ny ${nw}x$nh shown=$shown")
        surface.layoutParams = lp
    }

    private fun resolve(c: Cam): String? {
        if (!c.videoAuth) return c.video
        val src = Net.text("https://fl511.com/Camera/GetVideoUrl?imageId=${c.id}")?.trim()
        Log.i(TAG, "GetVideoUrl ${c.id}: ${src?.take(120)}")
        src ?: return null
        if (!src.startsWith("{")) return src.trim('"').takeIf { it.startsWith("http") }
        val token = Net.post("https://divas.cloud/VDS-API/SecureTokenUri/GetSecureTokenUriBySourceId", src)
            ?.trim()?.trim('"') ?: return null
        return c.video + token
    }

    companion object { private const val TAG = "PanesVideo" }
}
