package dev.vr.panes

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ScaleDrawable
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.SpannableString
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.text.style.ForegroundColorSpan
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Two states. Ambient: one camera fills the screen, live, with nothing on it but a name that fades away.
 * Browsing: press down and the shelf rises; whatever tile you rest on starts playing behind it.
 */
class MainActivity : Activity(), Video.Listener {
    private lateinit var repo: Repo
    private lateinit var video: Video
    private val ui = Handler(Looper.getMainLooper())

    private val cams = mutableListOf<Cam>()
    private val shotAt = HashMap<String, Long>()
    private var current = 0
    private var browsing = false
    private var generation = 0
    private var ticks = 0
    private var infoShown = false
    private var lastSwitch = 0L
    /** Where Back (or Up from the tabs) leaves the grid to: the shelf or the options that opened it; null for full screen. */
    private var gridBack: (() -> Unit)? = null
    private var selectDown = false
    private var selectLong = false

    private lateinit var front: Pane
    private lateinit var back: Pane
    private lateinit var scrim: View
    private lateinit var matte: Matte
    /** The blurred frame behind the chrome, which every glass surface lets through. */
    private var glassBg: Bitmap? = null
        set(v) {
            field = v
            val acts = actions as ViewGroup
            (listOf(city, browseAll, bYt, detail) + List(acts.childCount) { acts.getChildAt(it) }).forEach { it.invalidate() }
        }
    private lateinit var city: TextView
    private lateinit var browseAll: TextView
    private lateinit var bar: View
    private lateinit var actLoc: TextView
    private lateinit var actAll: View
    /** Where the picker was opened from (the Location option or the location button), for focus to return to. */
    private var pickOpener: View? = null
    private lateinit var info: ViewGroup
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var shelf: Shelf
    private lateinit var row: LinearLayout
    private lateinit var picker: View
    private lateinit var clock: View
    private lateinit var actions: View
    private lateinit var actFav: TextView
    private lateinit var actSize: TextView
    private lateinit var actSound: TextView
    private lateinit var bYt: TextView
    private lateinit var actInfo: View
    private lateinit var detail: LinearLayout
    private val hint by lazy { findViewById<View>(R.id.hint) }
    private val spinner by lazy { findViewById<View>(R.id.spinner) }
    private val time by lazy { findViewById<TextView>(R.id.time) }
    private val date by lazy { findViewById<TextView>(R.id.date) }
    private val grid by lazy { findViewById<View>(R.id.grid) }
    private val surface by lazy { findViewById<SurfaceView>(R.id.video) }
    private val gridBg by lazy { findViewById<ImageView>(R.id.gridBg) }
    private val gridScroll by lazy { findViewById<ScrollView>(R.id.gridScroll) }
    private val gridContent by lazy { findViewById<View>(R.id.gridContent) }
    private val pickBg by lazy { findViewById<ImageView>(R.id.pickBg) }
    private val pickCol by lazy { findViewById<View>(R.id.pickCol) }
    private val pickScroll by lazy { findViewById<ScrollView>(R.id.pickScroll) }
    private val pickList by lazy { findViewById<LinearLayout>(R.id.pickList) }
    private val pickTitle by lazy { findViewById<TextView>(R.id.pickTitle) }
    private val pickNote by lazy { findViewById<TextView>(R.id.pickNote) }
    private val gridList by lazy { findViewById<GridLayout>(R.id.gridList) }
    private val gridTitle by lazy { findViewById<TextView>(R.id.gridTitle) }
    private val gridCount by lazy { findViewById<TextView>(R.id.gridCount) }
    private val tabAll by lazy { findViewById<TextView>(R.id.tabAll) }
    private val tabFav by lazy { findViewById<TextView>(R.id.tabFav) }
    private var place: Place? = null
    private var weather: Weather.Now? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runCatching { HttpResponseCache.install(File(cacheDir, "http"), 8L shl 20) }
        repo = Repo(this)
        setContentView(R.layout.main)
        // The app is the ambient picture: while it is open the TV never dims, sleeps or starts its own screensaver.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        front =findViewById(R.id.heroA); back = findViewById(R.id.heroB); scrim = findViewById(R.id.scrim)
        matte = findViewById(R.id.matte)
        city = findViewById(R.id.city); info = findViewById(R.id.info)
        title = findViewById(R.id.title); meta = findViewById(R.id.meta)
        shelf = findViewById(R.id.shelf); row = findViewById(R.id.row); picker = findViewById(R.id.picker)
        video = Video(this, surface, findViewById(R.id.tube), this)
        clock = findViewById(R.id.clock); actions = findViewById(R.id.actions)
        actFav = findViewById(R.id.actFav); detail = findViewById(R.id.detail)
        actFav.setOnClickListener { toggleFavorite(current) }
        actInfo = findViewById<View>(R.id.actInfo).apply { setOnClickListener { toggleDetail() } }
        actAll = findViewById<View>(R.id.actAll).apply { setOnClickListener { openGrid() } }
        actSize = findViewById(R.id.actSize)
        actSize.setOnClickListener { repo.size = (repo.size + 1) % SIZES.size; describe(); cams.getOrNull(current)?.let { frame(it) }; touch() }
        bYt = findViewById(R.id.bYt)
        bYt.visibility = if (BuildConfig.PERSONAL) View.VISIBLE else View.GONE
        video.hideYt = repo.hideYt
        bYt.setOnClickListener { repo.hideYt = !repo.hideYt; video.hideYt = repo.hideYt; chrome(); rows(); touch() }
        actSound = findViewById(R.id.actSound)
        actSound.setOnClickListener { repo.sound = !repo.sound; video.sound = repo.sound; describe(); touch() }
        video.sound = repo.sound
        actLoc = findViewById(R.id.actLoc)
        bar = findViewById(R.id.bar)
        browseAll = findViewById(R.id.browseAll)
        browseAll.setOnClickListener { openGrid() }
        listOf(actLoc, city).forEach { b -> b.setOnClickListener { pickOpener = b; openPicker() } }
        val controls = listOf(actFav, actInfo, actAll, actLoc, actSize, actSound, city, browseAll, bYt)
        controls.forEach { b ->
            b.setOnFocusChangeListener { v, has -> lift(v, has, 1.06f); if (v === actSize || v === actSound || v === actLoc || v === bYt) rows() }
            b.setOnKeyListener { v, code, e ->
                // The bar's buttons sit over the posters: Down goes back to the one on screen (Left, Right and Up are in the layout).
                if (press(v, e, 1.06f)) true
                else if ((v === city || v === browseAll || v === bYt) && code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { currentTile()?.requestFocus(); true }
                else false
            }
        }
        // Optical balance: an icon reads as heavier than a letter, so its side takes 2dp less padding.
        listOf(actFav, actInfo, actAll, browseAll).forEach { it.setPaddingRelative(it.paddingStart - dp(2), it.paddingTop, it.paddingEnd, it.paddingBottom) }
        listOf(tabAll, tabFav).forEach { t ->
            t.setOnFocusChangeListener { v, has ->
                lift(v, has, 1.06f)
                if (has) switchTab(v === tabFav)
            }
            t.setOnKeyListener { v, _, e -> press(v, e, 1.06f) }
        }
        tickClock()

        // Shade pools only where text lives (clock top right, title bottom left); the picture stays clear.
        scrim.background = shade()
        // Controls and the detail card are glass over the picture, so they carry their own contrast anywhere.
        controls.forEach { it.glassy({ glassBg }) }
        detail.background = Glass(detail, dp(20).toFloat()) { glassBg }

        // The picker starts from what is already on disk, so it never offers a city with nothing live before the
        // network has answered.
        Net.async({ repo.places(offline = true) }) { if (places.isEmpty()) places = it }
        city.text = repo.city ?: " "
        shelf.post { setBrowsing(false, animate = false) }
        load()
    }

    override fun onStart() {
        super.onStart()
        ui.postDelayed(tick, 15_000); tickClock()
        cams.getOrNull(current)?.let { video.play(it) }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick); ui.removeCallbacks(clockTick); video.stop()
        HttpResponseCache.getInstalled()?.flush()
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); video.release(); super.onDestroy() }

    /** Every 15 s refresh labels; every minute re-pull the still if video is down; while browsing, the tiles. */
    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % 4 == 0 && !video.playing) loadPoster(current, crossfade = false)
            // Tiles are only seen while browsing; ambient refetching them just crowds the pool.
            if (browsing || gridOpen) refreshTiles()
            if (ticks % 60 == 0) loadWeather()
            tickClock(); describe()
            ui.postDelayed(this, 15_000)
        }
    }

    private var tilesAt = 0L

    /** Re-pull every tile at most every 3 minutes; dead road cams drop out on the way. */
    private fun refreshTiles() {
        if (System.currentTimeMillis() - tilesAt < 180_000) return
        tilesAt = System.currentTimeMillis()
        cams.toList().forEach { loadTile(it) }
    }

    /** The time where the cameras are, turning over on the minute rather than up to a tick late. */
    private val clockTick = Runnable { tickClock() }

    private fun tickClock() {
        val now = System.currentTimeMillis()
        time.text = Clock.time(this, weather?.zone, now)
        date.text = Clock.line(place?.name?.takeIf { (cams.getOrNull(current)?.miles ?: 0.0) < 100 }, weather, now)
        ui.removeCallbacks(clockTick); ui.postDelayed(clockTick, 60_000 - now % 60_000)
    }

    /** The half-degree cell the weather (and so the clock's zone) was last fetched for. */
    private var weatherCell = ""

    /**
     * Weather and time follow the camera on screen, not the chosen city: a favorite from across the world brings its own
     * sky and hour. Fetched again only when the camera crosses into another half-degree cell, or every 15 minutes.
     */
    private fun loadWeather(force: Boolean = true) {
        val cam = cams.getOrNull(current)
        val lat = cam?.lat ?: place?.lat ?: return; val lon = cam?.lon ?: place?.lon ?: return
        val cell = "${Math.round(lat * 2)},${Math.round(lon * 2)}"
        if (!force && cell == weatherCell) return
        // A new place drops the old line at once, so the clock never names a city the picture has left.
        if (cell != weatherCell) { weather = null; tickClock() }
        weatherCell = cell
        Net.async({ Weather.fetch(lat, lon) }) { if (cell == weatherCell) { weather = it; tickClock() } }
    }

    // ---- data ----

    private val retry = Runnable { load() }

    private fun load() {
        ui.removeCallbacks(retry)
        val gen = ++generation
        video.stop()
        title.text = "Finding cameras near you"; meta.text = " "; showInfo(hold = true)
        Net.pool.execute {
            val r = runCatching { repo.place()?.let { it to repo.cameras(it) } }.getOrNull()
            ui.post { if (gen == generation) loaded(r) }
            // After the cameras, so a slow server never holds them back.
            runCatching { repo.places() }.getOrNull()?.let { ui.post { places = it.filter { p -> p.name !in emptyCities } } }
        }
    }

    private fun loaded(r: Pair<Place, List<Cam>>?) {
        if (r == null) {
            title.text = "Can’t reach the cameras"; meta.text = "Check the connection. Trying again shortly."; hint.visibility = View.GONE
            showInfo(hold = true)
            ui.postDelayed(retry, 15_000); return
        }
        val (place, list) = r
        if (gridOpen) closeGrid()
        this.place = place; weather = null
        city.text = place.name; city.contentDescription = "Location, ${place.name}"
        cams.clear(); cams += list; row.removeAllViews(); shotAt.clear()
        loadWeather()
        val last = repo.lastCam(place.name)
        val favs = repo.favorites
        current = list.indexOfFirst { it.id == last }.takeIf { it >= 0 } ?: list.indexOfFirst { it.id !in favs }.coerceAtLeast(0)
        inFavs = list.getOrNull(current)?.id in favs
        if (cams.isEmpty()) { empty("Nothing live around here yet. Choose another city."); return }
        cams.forEach { row.addView(tile(it)) }
        shelf.scrollTo(0, 0)
        if (browsing) (currentTile() ?: city).requestFocus()
        switchTo(current)
        tilesAt = 0L; if (browsing || gridOpen) refreshTiles()
    }

    private fun empty(why: String) {
        if (gridOpen) closeGrid()
        title.text = "No live cameras nearby"; meta.text = why; showInfo(hold = true)
        front.setImageDrawable(null); back.setImageDrawable(null); matte.fadeTo(0f).start(); video.stop()
        hint.visibility = View.GONE; setBrowsing(true); city.requestFocus()
    }

    private fun loadTile(cam: Cam) {
        val gen = generation
        Net.pool.execute {
            val f = Snap.fetch(cam, resources.displayMetrics.widthPixels / 3)
            ui.post {
                if (gen != generation) return@post
                // A missed still never pulls the camera someone is watching live.
                if (f == null) { if (cam.road && !(cams.getOrNull(current) == cam && video.playing)) drop(cam) } else {
                    shotAt[cam.id] = f.takenAt
                    setTileImage(cam, f.bitmap)
                    if (cams.indexOf(cam) == current && front.drawable == null) showPoster(cam, f.bitmap.soft(), false)
                }
            }
        }
    }

    /** Dead, frozen and slow feeds leave the shelf instead of sitting there broken. [remember] keeps it hidden for 30 min; a timeout or a missed still only hides it for this session. */
    private fun drop(cam: Cam, remember: Boolean = false) {
        val i = cams.indexOf(cam)
        if (i < 0) return
        if (remember) repo.markDead(cam.id)
        val hadFocus = row.getChildAt(i).hasFocus()
        val g = if (gridOpen) shown.indexOf(cam) else -1
        val gridHadFocus = g >= 0 && gridList.getChildAt(g).hasFocus()
        if (g >= 0) { gridList.removeViewAt(g); shown = shown - cam }
        cams.removeAt(i); row.removeViewAt(i)
        if (i < standIn) standIn-- else if (i == standIn) standIn = -1
        if (cams.isEmpty()) { empty("Every camera around here is offline right now."); return }
        when {
            i < current -> current--
            i == current -> cams.indices.filter { isFav(it) == inFavs }
                .let { lane -> switchTo(lane.firstOrNull { it >= i } ?: lane.lastOrNull() ?: minOf(i, cams.size - 1)) }
        }
        if (hadFocus) currentTile()?.requestFocus()
        if (gridOpen) { countGrid(); if (gridHadFocus) (cell(minOf(g, shown.size - 1)) ?: openTab()).requestFocus() }
    }

    private fun tileImage(cam: Cam): ImageView? =
        cams.indexOf(cam).takeIf { it >= 0 }?.let { row.getChildAt(it)?.findViewById(R.id.tileImg) }

    /** The shelf tile and, while the grid is open, its twin there. */
    private fun setTileImage(cam: Cam, b: Bitmap) {
        tileImage(cam)?.setImageBitmap(b)
        gridCard(cam)?.findViewById<ImageView>(R.id.tileImg)?.setImageBitmap(b)
    }

    // ---- playing ----

    private fun switchTo(i: Int) {
        val cam = cams.getOrNull(i) ?: return
        current = i; lastSwitch = System.currentTimeMillis()
        // The details card belongs to the camera it was opened on.
        if (detailOn) showDetail(false)
        place?.let { repo.rememberCam(it.name, cam.id) }
        onAudio(cam, has = false)
        tickClock()
        // A new camera starts from no live surface, so its window glides instead of snapping.
        if ((video.playing || video.connecting) && !video.showing(cam)) video.stop()
        frame(cam)
        loadPoster(i, crossfade = true)
        video.play(cam)
        describe()
        if (!browsing) showInfo()
    }

    override fun onLive(cam: Cam) {
        if (cams.getOrNull(current) != cam) return
        shotAt[cam.id] = System.currentTimeMillis()
        // Under live video the panes only wait for a stall; a 40 s push-in there is wasted work.
        listOf(front, back).forEach { it.animate().cancel(); it.fadeTo(0f).start() }
        describe(); chrome()
        // A stream with no published still gets its poster from its own first frames.
        if (!video.youtube && tileImage(cam)?.drawable == null) ui.postDelayed({
            if (cams.getOrNull(current) == cam && video.playing && !video.youtube)
                video.grab(dp(TILE_W) * 2, dp(TILE_H) * 2) { it?.let { b -> setTileImage(cam, b) } }
        }, 1_500)
    }

    override fun onSize(cam: Cam, width: Int, height: Int) {
        if (!cam.road) return
        cam.width = width
        if (cam.height != height) { cam.height = height; repo.rememberHeight(cam.id, height) }
        if (cams.getOrNull(current) == cam) { describe(); frame(cam) }
    }

    /**
     * Puts the panes, the video and the matte on one window for [cam], so a stall or a sharper still never shifts the picture.
     * At Picture size Smaller the picture sits in a window on its own blurred frame, like a tvOS poster, centered in the
     * band between the clock (top right) and the title block (bottom left). Its size comes from that band with the actions
     * closed and never changes; opening them only slides it up. Full screen runs edge to edge, as does anything under the shelf.
     */
    private fun frame(cam: Cam) {
        val m = resources.displayMetrics; val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
        val cap = Pane.CAPS[repo.size]
        var k = if (cam.height == 0) 1f
            else Pane.window(w, h, (cam.width.takeIf { it > 0 } ?: cam.height * 16 / 9).toFloat(), cam.height.toFloat(), cam.trim, cap)
        // Under the info card the picture is a backdrop, as in tvOS: edge to edge, with the text over it. The shelf
        // leaves it as it is: browsing must not make the picture jump in size.
        if (detailOn) k = 1f
        // The band between the clock and the title block, with the actions closed: opening them only slides the window up.
        val gap = dp(16); val clockH = clock.height.takeIf { it > 0 } ?: dp(72)
        val top = dp(40) + clockH + gap
        val shift = if (hint.visibility == View.VISIBLE) 0 else dp(52)
        val under = dp(40) + (info.height.takeIf { it > 0 } ?: dp(120)) - shift + gap
        if (!detailOn && repo.size == 0) k = minOf(k, .9f, (h - top - under) / h)
        val lift = if (actionsOpen && k < 1f) dp(52) else 0
        val cy = if (k < 1f) ((top + (h - under - top) / 2 - lift) / h).coerceIn(k / 2, 1 - k / 2) else .5f
        // The panes and matte ease into a new window; a live surface can only snap, so then everything snaps with it,
        // the matte's opacity included: a half-faded matte would show the surface's trimmed overflow through it.
        val glide = !video.playing
        listOf(front, back).forEach { it.place(k, cy, glide) }
        video.place(k, cy)
        matte.place(k, cy, glide)
        val veil = if (k < 1f) 1f else 0f
        if (glide) matte.fadeTo(veil).start() else { matte.animate().cancel(); matte.alpha = veil }
        if (windowed != (k < 1f)) { windowed = k < 1f; chrome() }
        // The chrome keeps the screen's own corners in every mode, as Aerial does: the clock top right, the title bottom
        // left. Only the picture ever snaps; the text always eases. With the actions open the window rises into the
        // clock's room, so the clock steps aside until they close. Closed, the title block drops into the empty actions
        // slot, which is the room the window's band was measured without.
        val drop = if (k < 1f && !actionsOpen) shift.toFloat() else 0f
        info.animate().translationY(if (browsing) -shelf.height.toFloat() else drop).setDuration(380).setInterpolator(ease).start()
        val textW = (w - 2 * dp(48)).toInt()
        title.maxWidth = textW; meta.maxWidth = textW
        clock.fadeTo(if (lift > 0 || clockOverPlayer) 0f else 1f).start()
    }

    override fun onStall(cam: Cam) {
        if (cams.getOrNull(current) != cam) return
        front.fadeTo(1f).start()
        describe(); chrome()
    }

    /** A real loss: a scenic cam is dead and leaves the list; road cams fall back to stills and go only when those fail. */
    override fun onLost(cam: Cam, dead: Boolean) {
        if (cams.getOrNull(current) != cam) return
        if (!cam.road) { drop(cam, remember = dead); return }
        front.fadeTo(1f).start()
        describe(); chrome()
    }

    /** The picture sits in a window, with the chrome beside it on the matte rather than over it. */
    private var windowed = false

    /**
     * YouTube forbids drawing over its player: while one is live in ambient, the scrim goes and, unless the player is
     * windowed (the title then sits beside it, on the matte), so does the title. The matte is scrim enough for a window.
     * The clock stays up over the player only while the personal-build "YouTube elements" row says Hidden; otherwise it steps aside.
     */
    private fun chrome(d: Long = 380) {
        val bare = video.youtube && video.playing && !browsing && !actionsOpen
        scrim.fadeTo(if (browsing) 1f else if (bare || windowed) 0f else .85f, d).start()
        if (bare && !windowed) { ui.removeCallbacks(timeout); hideInfo.run() }
        if (!actionsOpen) clock.fadeTo(if (clockOverPlayer) 0f else 1f, d).start()
        // Nothing may sit over a visible YouTube player, so a card opened before it started comes down.
        if (detailOn && video.youtube && video.playing && !repo.hideYt) showDetail(false)
    }

    /** YouTube forbids anything in front of its player, so outside a personal build the clock steps aside while one plays. */
    private val clockOverPlayer get() = !repo.hideYt && video.youtube && video.playing && !windowed

    private fun loadPoster(i: Int, crossfade: Boolean) {
        val cam = cams.getOrNull(i) ?: return
        val gen = generation
        val small = (tileImage(cam)?.drawable as? BitmapDrawable)?.bitmap
        // The shelf's thumbnail stands in, softened, and the full frame comes into focus over it.
        if (crossfade && small != null) showPoster(cam, small.soft(), true)
        // No still is coming (a stream without a published frame): fade the last camera's picture out rather than
        // leave it under this camera's name.
        else if (crossfade && cam.still.isEmpty() && !cam.road) listOf(front, back).forEach { p ->
            p.fadeTo(0f).withEndAction { if (p.alpha == 0f) p.setImageDrawable(null) }.start()
        }
        Net.pool.execute {
            val f = Snap.fetch(cam, resources.displayMetrics.widthPixels)
            ui.post {
                if (gen != generation || cams.getOrNull(current) != cam) return@post
                // No full frame: the sharp thumbnail replaces the softened one, so a stall never reveals a blur.
                if (f == null) { if (cam.road && !video.playing) drop(cam) else if (small != null) showPoster(cam, small, false); return@post }
                if (!video.playing) shotAt[cam.id] = f.takenAt
                // Until the stream reports its size, the still (decoded at native size when it's small) says how soft the source is.
                if (cam.road && cam.height == 0) { cam.width = f.bitmap.width; cam.height = f.bitmap.height; frame(cam) }
                showPoster(cam, f.bitmap, crossfade && small == null)
                describe()
            }
        }
    }

    private fun showPoster(cam: Cam, b: Bitmap, crossfade: Boolean) {
        // The matte keeps the same frame behind the window, blurred; cheap enough to refresh even while it's hidden.
        // The glass lets the same blurred frame through.
        b.haze(cam.trim).let { matte.show(it); glassBg = it }
        // Only the pane taking the new picture is refit; the outgoing one keeps its framing through its fade.
        // Live video is never covered: a frame that lands late only waits in the faded pane for a stall.
        if (video.playing) { front.trim = cam.trim; front.setImageBitmap(b); return }
        if (front.drawable == null) {
            front.trim = cam.trim; front.setImageBitmap(b)
            if (crossfade) { front.alpha = 0f; front.fadeTo(1f).start() } else front.alpha = 1f
            // Nothing showed before, so the drift starts over rather than inherit the last camera's push-in.
            front.scaleX = 1f; front.scaleY = 1f; drift(front)
            return
        }
        // A new camera starts its drift afresh. A sharper or newer frame of the same one dissolves in place at the
        // same scale, so the picture comes into focus instead of popping.
        val incoming = back; val outgoing = front
        front = incoming; back = outgoing
        incoming.trim = cam.trim; incoming.setImageBitmap(b); incoming.alpha = 0f
        val s = if (crossfade) 1f else outgoing.scaleX
        incoming.scaleX = s; incoming.scaleY = s; drift(incoming)
        incoming.fadeTo(1f).start()
        outgoing.fadeTo(0f).start()
    }

    // ---- chrome ----

    private val hideInfo = Runnable { infoShown = false; if (!browsing && !actionsOpen) info.fadeTo(0f).start() }
    /** The title waits out a connecting stream, so a camera with nothing on screen yet never reads as a dead one. */
    private val timeout: Runnable = Runnable { if (video.connecting) ui.postDelayed(timeout, 1_000) else hideInfo.run() }
    private val idle = Runnable {
        if (pickerOpen || cams.isEmpty()) return@Runnable
        if (browsing) setBrowsing(false) else if (actionsOpen) closeActions()
    }

    private fun showInfo(hold: Boolean = false) {
        // Open actions keep the title up; only ambient lets it time out.
        val held = hold || actionsOpen
        ui.removeCallbacks(timeout)
        // Nothing draws over a YouTube player in ambient: the title only comes back while browsing or in actions.
        if (!held && !browsing && !windowed && video.youtube && video.playing) { hideInfo.run(); return }
        // Only the alpha is replaced here; a cancel() would also kill a slide that setBrowsing just started.
        info.fadeTo(1f, 160).start()
        infoShown = !held
        if (!held) ui.postDelayed(timeout, 5_000)
    }

    private fun setBrowsing(on: Boolean, animate: Boolean = true) {
        browsing = on
        shelf.descendantFocusability = if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (on) { if (cams.isNotEmpty()) repo.see("b"); closeActions(quiet = true); showInfo(hold = true); refreshTiles(); liveGlass() }
        val d = if (animate) 380L else 0L
        val hidden = (shelf.height + dp(40)).toFloat()
        shelf.animate().translationY(if (on) 0f else hidden).setDuration(d).setInterpolator(ease).start()
        bar.fadeTo(if (on) 1f else 0f, d).start()
        updateHint()
        chrome(d)
        cams.getOrNull(current)?.let { frame(it) }
        // No cameras, no "All cameras": the bar never offers a button that leads nowhere.
        browseAll.visibility = if (cams.isEmpty()) View.GONE else View.VISIBLE
        city.isFocusable = on; browseAll.isFocusable = on; bYt.isFocusable = on
        ui.removeCallbacks(idle)
        if (on) {
            // A shelf of nothing but favorites has no tile to land on; the location label takes focus instead.
            currentTile()?.let { shelf.smoothScrollTo(it.left - row.paddingStart, 0); it.requestFocus() } ?: city.requestFocus()
            ui.postDelayed(idle, 15_000)
        } else { if (!actionsOpen) info.requestFocus(); if (cams.isNotEmpty()) showInfo() }
    }

    private fun updateHint() {
        hint.visibility = if (browsing || actionsOpen || cams.isEmpty() || video.youtube || (repo.seen("a") && repo.seen("b"))) View.GONE else View.VISIBLE
    }

    // A flag, not visibility: actions stay visible through their 160ms close fade, and keys then must not reach them.
    private var actionsOpen = false

    private fun openActions() {
        actionsOpen = true; repo.see("a")
        showInfo(hold = true)
        hint.visibility = View.GONE
        showDetail(false)
        actions.visibility = View.VISIBLE
        actions.fadeTo(1f, 220).start()
        info.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        actFav.requestFocus()
        touch(); chrome(220); liveGlass()
        cams.getOrNull(current)?.let { frame(it) }
    }

    /** Glass shows what plays now: one blurred grab of the live frame when chrome comes up (video moves on; a still doesn't). */
    private fun liveGlass() {
        val cam = cams.getOrNull(current)
        // A grab that lands after a switch shows the last camera: the new one's poster haze stays.
        video.grab(160, 90) { live -> if (live != null && cams.getOrNull(current) == cam) glassBg = live.blurred(6) }
    }

    private fun closeActions(quiet: Boolean = false) {
        if (!actionsOpen) return
        actionsOpen = false
        showDetail(false)
        // The end action also runs when a reopen cancels this fade, so only hide once the fade really finished.
        actions.fadeTo(0f, 160).withEndAction { if (actions.alpha == 0f) actions.visibility = View.GONE }.start()
        // Park focus on the title block; a bare clearFocus() lets Android hand it straight back to the fading buttons.
        info.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        info.requestFocus()
        updateHint()
        if (!browsing) ui.removeCallbacks(idle)
        if (!quiet) showInfo()
        cams.getOrNull(current)?.let { frame(it) }
        chrome(220)
    }

    private fun toggleDetail() {
        if (detailOn) { showDetail(false); return }
        // The card sits high on the left, over the picture; a visible YouTube player may never be drawn over.
        if (video.youtube && video.playing && !repo.hideYt) return
        val cam = cams.getOrNull(current) ?: return
        detail.removeAllViews(); showDetail(true)
        // The small spinner, held back a beat so an answer that comes at once never flashes it.
        detail.addView(Spinner(this).apply {
            alpha = 0f; animate().alpha(1f).setStartDelay(600).setDuration(380).setInterpolator(ease).start()
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(6) })
        Net.async({ (if (cam.road) repo.details(cam) else null) ?: emptyMap() }) { d ->
            if (cams.getOrNull(current) != cam || !detailOn) return@async
            detail.removeAllViews()
            d.forEach { (k, v) -> detail.addView(detailRow(k, v)) }
            detail.addView(detailRow("Feed", if (video.playing) "Live video" else "Stills, refreshed each minute"))
            detail.addView(detailRow("Source", cam.credit))
            if (d.isEmpty() && cam.road) detail.addView(detailRow("Details", "Unavailable right now"))
        }
    }

    private var detailOn = false

    private fun showDetail(on: Boolean) {
        detailOn = on
        if (on) { detail.visibility = View.VISIBLE; detail.fadeTo(1f, 220).start() }
        else detail.fadeTo(0f, 160).withEndAction { if (detail.alpha == 0f) detail.visibility = View.GONE }.start()
        cams.getOrNull(current)?.let { frame(it) }
        touch()
    }

    /** Detail rows sit high in the picture, on their glass card; the OnPicture shadow keeps them crisp on it. */
    private fun detailRow(k: String, v: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        fun label(t: String, color: Int) = TextView(context).apply {
            text = t; textSize = 15f; setTextColor(getColor(color)); onPicture()
        }
        addView(label(k, R.color.on_picture_dim).apply { width = dp(104) })
        addView(label(v, R.color.text))
    }

    private fun touch() {
        if (!browsing && !actionsOpen) return
        ui.removeCallbacks(idle)
        ui.postDelayed(idle, if (browsing) 15_000L else if (detailOn) 25_000L else 12_000L)
    }

    /** [note] briefly takes the favorite's place at the head of the line ("Added to favorites"). */
    private fun describe(note: String? = null) {
        val cam = cams.getOrNull(current) ?: return
        loadWeather(force = false)
        title.text = cam.title
        val fav = cam.id in repo.favorites
        // The heart says which: filled is a favorite, outlined is not.
        actFav.text = "Favorite"
        swapIcon(actFav, if (fav) R.drawable.ic_fav else R.drawable.ic_fav_outline)
        rows()
        meta.text = listOfNotNull(
            note ?: if (fav) "Favorite" else null,
            cam.quality.ifEmpty { null },
            when { video.playing -> "Live"; video.connecting -> "Connecting"; else -> age(cam) },
        ).joinToString("  ·  ")
        // Held back a beat, as tvOS does, so a stream that starts quickly never flashes it.
        if (!video.connecting) { spinner.animate().cancel(); spinner.visibility = View.GONE }
        else if (spinner.visibility != View.VISIBLE) {
            spinner.alpha = 0f; spinner.visibility = View.VISIBLE
            spinner.animate().alpha(1f).setStartDelay(600).setDuration(380).setInterpolator(ease).start()
        }
        updateHint()
    }

    /** The settings rows, as tvOS sets them: the label, then its value a step dimmer in whichever ink the platter is using. */
    private fun rows() {
        fun row(v: TextView, label: String, value: String) {
            val ink = getColor(if (v.isFocused) R.color.on_platter else R.color.text)
            v.text = SpannableString("$label  $value").apply {
                setSpan(ForegroundColorSpan(ink and 0xFFFFFF or (0x99 shl 24)), length - value.length, length, 0)
            }
        }
        row(actLoc, "Location", place?.name ?: repo.city ?: "Automatic")
        row(actSize, "Picture size", SIZES[repo.size])
        row(actSound, "Sound", if (repo.sound) "On" else "Off")
        row(bYt, "YouTube", if (repo.hideYt) "Hidden" else "Shown")
        // A card may not be drawn over a visible YouTube player; only the personal "Hidden" mode allows it.
        val noCard = video.youtube && video.playing && !repo.hideYt
        actInfo.visibility = if (noCard) View.GONE else View.VISIBLE
    }

    /** The Sound row only exists for a camera that can be heard; focus never stays on a row that goes. */
    override fun onAudio(cam: Cam, has: Boolean) {
        if (cams.getOrNull(current) != cam) return
        if (!has && actSound.isFocused) actSize.requestFocus()
        actSound.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun age(cam: Cam): String {
        val t = shotAt[cam.id] ?: return "Loading"
        return if (System.currentTimeMillis() - t < 60_000) "Just now" else "Still from " +
            DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }

    // ---- shelf ----

    // Landing on the camera already live must not reload it: that would crossfade the still back over the video.
    private val playSoon = Runnable {
        if (browsing && focusedTile() != standIn && (focusedTile() != current || !video.playing)) { inFavs = false; switchTo(focusedTile()) }
    }

    private fun focusedTile() = row.focusedChild?.let { row.indexOfChild(it) } ?: current

    /** Favorites live only in their tab: their shelf tiles stay in the row but gone, so row and [cams] indices still match. */
    private fun isFav(i: Int) = cams.getOrNull(i)?.id in repo.favorites

    /** The nearest tile past [i] in direction [d] that is on the shelf. */
    private fun nextTile(i: Int, d: Int) = generateSequence(i + d) { it + d }.takeWhile { it in 0 until row.childCount }
        .firstOrNull { row.getChildAt(it).visibility == View.VISIBLE }

    /** Left and Right in full screen step through favorites, not the shelf: the camera playing was picked from their tab. */
    private var inFavs = false

    /** A tile focused only because the favorite playing has none: it waits for a move before it plays. */
    private var standIn = -1

    /** The current camera's tile, or the nearest one on the shelf while a favorite plays. */
    private fun currentTile(): View? {
        val i = if (row.getChildAt(current)?.visibility == View.VISIBLE) current else nextTile(current, -1) ?: nextTile(current, 1)
        standIn = if (i != current) i ?: -1 else -1
        return i?.let { row.getChildAt(it) }
    }

    /** A poster: rounded frame, the still, a specular glint for focus, and the favorite mark. Shelf and grid share it. */
    private fun card(cam: Cam, w: Int, h: Int, named: Boolean = false) = FrameLayout(this).apply {
        isFocusable = true; isClickable = true; isLongClickable = true
        contentDescription = cam.title
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(getColor(R.color.tile)) }
        outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        outlineSpotShadowColor = 0xFF000000.toInt()
        alpha = POSTER_DIM
        // Until (or unless) a still arrives, a shelf tile names its camera instead of sitting there blank.
        // Grid cards skip it: their caption already sits underneath.
        if (named) addView(TextView(context).apply {
            text = cam.title; textSize = 15f; setTextColor(getColor(R.color.text_dim))
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(Pane(context).apply { id = R.id.tileImg; trim = cam.trim },FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(sheen().apply { translationX = -w.toFloat() }, FrameLayout.LayoutParams(w / 2, h * 2).apply { topMargin = -h / 2 })
        addView(ImageView(context).apply {
            id = R.id.tileFav; setImageResource(R.drawable.ic_fav)
            visibility = if (cam.id in repo.favorites) View.VISIBLE else View.GONE
        }, FrameLayout.LayoutParams(dp(16), dp(16)).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(8); marginEnd = dp(8)
        })
    }

    /** The specular band [glint] sweeps across a focused poster or row, parked off its left edge until then. */
    private fun sheen() = View(this).apply {
        id = R.id.tileSheen; rotation = 18f
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF))
    }

    private fun glint(card: View) {
        val w = card.width
        // Focus can land before layout; a sweep over zero width would park the sheen on the card.
        if (w == 0) return
        card.findViewById<View>(R.id.tileSheen).run {
            animate().cancel(); translationX = -w / 2f
            animate().translationX(w * 1.1f).setDuration(700).setInterpolator(ease).start()
        }
    }

    private fun tile(cam: Cam): View {
        val t = card(cam, dp(TILE_W), dp(TILE_H), named = true)
        t.layoutParams = LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { marginEnd = dp(22) }
        if (cam.id in repo.favorites) t.visibility = View.GONE
        t.setOnFocusChangeListener { v, has ->
            lift(v, has, 1.1f, POSTER_DIM)
            if (has) {
                glint(v)
                if (row.indexOfChild(v) != standIn) standIn = -1
                shelf.smoothScrollTo(v.left - row.paddingStart, 0)
                ui.removeCallbacks(playSoon); ui.postDelayed(playSoon, 450)
            }
        }
        t.setOnKeyListener { v, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val i = row.indexOfChild(v)
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val d = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    val n = nextTile(i, d)
                    if (n != null) row.getChildAt(n).requestFocus() else if (e.repeatCount == 0) bump(d, v); true
                }
                KeyEvent.KEYCODE_DPAD_UP -> { city.requestFocus(); true }
                // Below the posters there is nothing to go to: every camera is "All cameras", up in the bar.
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        }
        t.setOnClickListener {
            val i = row.indexOfChild(it)
            inFavs = false
            if (i != current) switchTo(i)
            setBrowsing(false)
        }
        t.setOnLongClickListener { toggleFavorite(row.indexOfChild(it)); true }
        return t
    }

    private fun step(d: Int) {
        if (cams.isEmpty()) return
        lastSwitch = System.currentTimeMillis()
        if (gridOpen) {
            val from = gridList.focusedChild?.let { gridList.indexOfChild(it) } ?: return
            cell(from + d)?.requestFocus() ?: bump(d, cell(from))
            return
        }
        val from = if (browsing) focusedTile() else current
        // Full screen, Left and Right stay in the list the camera was picked from: the shelf, or the favorites ([inFavs]).
        val n = if (browsing) nextTile(from, d)
            else generateSequence(from + d) { it + d }.takeWhile { it in cams.indices }.firstOrNull { isFav(it) == inFavs }
        when {
            n == null -> if (browsing) bump(d, row.getChildAt(from)) else { bump(d); showInfo() }
            browsing -> row.getChildAt(n)?.requestFocus()
            else -> switchTo(n)
        }
    }

    /** A rubber-band at the ends of the list. Never over a live YouTube player. */
    private fun bump(d: Int, v: View? = info) {
        if (v == null || (v === info && video.youtube && video.playing && !browsing)) return
        // Relative to where the view rests: a windowed title sits in from the margin and must come back there.
        val rest = v.translationX
        val out = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, rest - d * dp(10)).setDuration(160).apply { interpolator = ease }
        val back = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, rest).setDuration(200).apply { interpolator = ease }
        AnimatorSet().apply { playSequentially(out, back) }.start()
    }

    /** Focus as the cursor: grow, cast one soft shadow below, and ([dim]) leave the rest a step darker. */
    private fun lift(v: View, has: Boolean, scale: Float, dim: Float = 1f) {
        v.outlineAmbientShadowColor = 0
        // Platter buttons keep their own fades (the city label fades out with the shelf), so only posters touch alpha.
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
            .apply { if (dim < 1f) alpha(if (has) 1f else dim) }
            .translationZ(if (has) dp(10).toFloat() else 0f).setDuration(220).setInterpolator(ease).start()
    }

    /**
     * Select answers as tvOS does: the focused control sinks to 0.96 of its lifted [scale] while held and comes back
     * on release, 150ms each way. Never consumes the key, so the click still lands.
     */
    private fun press(v: View, e: KeyEvent, scale: Float): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && e.keyCode != KeyEvent.KEYCODE_ENTER) return false
        val s = when {
            e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> scale * .96f
            e.action == KeyEvent.ACTION_UP -> scale
            else -> return false
        }
        v.animate().scaleX(s).scaleY(s).setDuration(150).setInterpolator(ease).start()
        return false
    }

    /**
     * A state icon (the favorite heart) cross-fades in when the state changes: from a quarter size and clear to full
     * over 300ms. The first show of a camera just lands, so nothing animates that didn't change.
     */
    private fun swapIcon(v: TextView, res: Int) {
        if (v.tag == res) return
        val first = v.tag == null; v.tag = res
        val d = ScaleDrawable(getDrawable(res), Gravity.CENTER, 1f, 1f).apply { level = 10_000 }
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
        if (first) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { val t = it.animatedValue as Float; d.level = (2_500 + 7_500 * t).toInt(); d.alpha = (255 * t).toInt() }
            start()
        }
    }

    private fun toggleFavorite(i: Int) {
        val cam = cams.getOrNull(i) ?: return
        val on = repo.toggleFavorite(cam)
        listOfNotNull(row.getChildAt(i), gridCard(cam)).forEach {
            it.findViewById<View>(R.id.tileFav).visibility = if (on) View.VISIBLE else View.GONE
        }
        // A new favorite leaves the shelf for its tab, handing focus to a neighbour; an old one comes back.
        row.getChildAt(i)?.let { t ->
            if (on && t.hasFocus()) (nextTile(i, 1) ?: nextTile(i, -1))?.let { row.getChildAt(it).requestFocus() } ?: city.requestFocus()
            t.visibility = if (on) View.GONE else View.VISIBLE
        }
        if (gridOpen && on != shownFav) dropFromGrid(cam)
        if (i == current) {
            describe(if (on) "Added to favorites" else "Removed from favorites")
            showInfo(hold = browsing || actionsOpen)
        }
    }

    // ---- grid ----

    private var gridOpen = false
    /** The tab on screen: All, unless every camera here is a favorite. */
    private var shownFav = false
    /** The cameras in the open tab, in grid order. */
    private var shown = listOf<Cam>()
    private var gridMove = 0L

    private fun cell(i: Int): View? = gridList.getChildAt(i)?.findViewById(R.id.tileCard)
    private fun gridCard(cam: Cam?): View? = if (gridOpen && cam != null) cell(shown.indexOf(cam)) else null
    private fun openTab(): View = if (shownFav) tabFav else tabAll

    /** Every camera at once, tvOS-style: three across, each name under its poster, brightening with focus. */
    private fun openGrid() {
        if (cams.isEmpty()) return
        gridBack = when {
            browsing -> { { setBrowsing(true) } }
            actionsOpen -> { { openActions(); actAll.requestFocus() } }
            else -> null
        }
        closeActions(quiet = true)
        // Favorites opens only when every camera here is one and All would be empty.
        val favs = repo.favorites
        shownFav = cams.all { it.id in favs }
        if (browsing) setBrowsing(false)
        gridOpen = true
        // Cancel a closing fade first: its end action empties the grid, and must not run after the cells are rebuilt.
        grid.animate().cancel()
        // A tab switch cut short by the last close may have left its parts faded out.
        listOf(gridTitle, gridCount, gridList).forEach { it.animate().cancel(); it.alpha = 1f }
        fillGrid()
        backdrop(gridBg)
        grid.visibility = View.VISIBLE
        grid.alpha = .6f
        grid.fadeTo(1f, 220).start()
        gridContent.translationY = dp(24).toFloat()
        gridContent.animate().translationY(0f).setDuration(380).setInterpolator(ease).start()
        // Focus lands on the camera on screen, scrolled to mid-screen once laid out.
        (gridCard(cams.getOrNull(current)) ?: cell(0) ?: openTab()).requestFocus()
        gridList.post { gridList.focusedChild?.let { frameRow(it, smooth = false) } }
    }

    /** A camera that changes tabs (favorited in All, unfavorited in Favorites) leaves the open one; focus moves to the card that takes its place, or the tab. */
    private fun dropFromGrid(cam: Cam) {
        val j = shown.indexOf(cam).takeIf { it >= 0 } ?: return
        val had = gridList.getChildAt(j)?.hasFocus() == true
        shown = shown - cam
        gridList.removeViewAt(j)
        countGrid()
        if (had) (cell(j.coerceAtMost(shown.size - 1)) ?: openTab()).requestFocus()
    }

    /** Builds the open tab: everything here but favorites, or only favorites (from anywhere). */
    private fun fillGrid() {
        val favs = repo.favorites
        // A rebuild under focus (a quick Down during a tab switch) must hand focus on, not drop it on a hidden view.
        val had = gridList.hasFocus()
        shown = cams.filter { (it.id in favs) == shownFav }
        tabAll.isSelected = !shownFav; tabFav.isSelected = shownFav
        gridTitle.text = if (shownFav) "Favorites" else place?.name ?: "All cameras"
        val cols = gridList.columnCount; val gap = dp(36)
        val w = (resources.displayMetrics.widthPixels - 2 * dp(64) - (cols - 1) * gap) / cols
        gridList.removeAllViews()
        shown.forEach { cam ->
            gridList.addView(gridTile(cam, w, w * 9 / 16), GridLayout.LayoutParams().apply {
                width = w; setMargins(gap / 2, 0, gap / 2, dp(30))
            })
        }
        countGrid()
        gridScroll.scrollTo(0, 0)
        if (had) (gridCard(cams.getOrNull(current)) ?: cell(0) ?: openTab()).requestFocus()
        gridList.post { gridCard(cams.getOrNull(current))?.takeIf { it.hasFocus() }?.let { frameRow(it.parent as View, smooth = false) } }
    }

    /** The old tab's cameras fade out and the new one's fade in, so the switch never flashes an empty screen. */
    private fun switchTab(fav: Boolean) {
        if (shownFav == fav) return
        shownFav = fav
        tabAll.isSelected = !fav; tabFav.isSelected = fav
        val parts = listOf(gridTitle, gridCount, gridList)
        parts.forEach { it.fadeTo(0f, 120).start() }
        // Only the list's fade carries the rebuild; a quick flick back cancels it and the newer switch takes over.
        gridList.fadeTo(0f, 120).withEndAction {
            if (!gridOpen || shownFav != fav) return@withEndAction
            fillGrid()
            parts.forEach { it.fadeTo(1f, 220).start() }
        }.start()
    }

    /** [back] returns to whatever opened the grid (Back); anything else lands on the full-screen picture. */
    private fun closeGrid(back: Boolean = false) {
        if (!gridOpen) return
        gridOpen = false
        // Park focus on the title block, or Android hands it to the hidden shelf when the grid goes away.
        info.requestFocus()
        grid.fadeTo(0f, 160).withEndAction {
            grid.visibility = View.GONE; gridList.removeAllViews(); shown = emptyList(); gridBg.setImageDrawable(null)
        }.start()
        val to = gridBack.takeIf { back }; gridBack = null
        to?.invoke() ?: showInfo()
    }

    /** The still, shrunk and blurred, at once; then the live frame, if video is up, a frame or two later. */
    private fun backdrop(into: ImageView) {
        into.setImageBitmap((front.drawable as? BitmapDrawable)?.bitmap?.haze(front.trim))
        video.grab(160, 90) { live -> if (live != null && into.isShown) into.setImageBitmap(live.blurred(6)) }
    }

    private fun countGrid() {
        gridCount.text = when {
            shown.isEmpty() && shownFav -> "Hold Select on any camera to add it here."
            shown.isEmpty() -> "Every camera here is in Favorites."
            shown.size == 1 -> "1 camera"
            else -> "${shown.size} cameras"
        }
    }

    /** Scroll so the focused row sits mid-screen, the way tvOS keeps focus near the center of a long grid. */
    private fun frameRow(cell: View, smooth: Boolean = true) {
        val y = if (gridList.indexOfChild(cell) < gridList.columnCount) 0
            else gridScroll.paddingTop + gridList.top + cell.top + cell.height / 2 - gridScroll.height / 2
        if (smooth) gridScroll.smoothScrollTo(0, y) else gridScroll.scrollTo(0, y)
    }

    private fun gridTile(cam: Cam, w: Int, h: Int): View {
        val name = TextView(this).apply {
            text = cam.title; textSize = 15f; setTextColor(getColor(R.color.text_dim))
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1
            setPadding(dp(2), dp(12), dp(2), 0)
        }
        val sub = TextView(this).apply {
            text = cam.quality
            textSize = 13f; setTextColor(getColor(R.color.text_faint))
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        val c = card(cam, w, h).apply { id = R.id.tileCard }
        (tileImage(cam)?.drawable as? BitmapDrawable)?.bitmap?.let { c.findViewById<ImageView>(R.id.tileImg).setImageBitmap(it) }
        c.setOnFocusChangeListener { v, has ->
            lift(v, has, 1.08f, POSTER_DIM)
            // The poster grows 4% past its bottom edge; the name steps down with it so the two never touch.
            listOf(name, sub).forEach { it.animate().translationY(if (has) h * .04f else 0f).setDuration(220).setInterpolator(ease).start() }
            name.setTextColor(getColor(if (has) R.color.text else R.color.text_dim))
            name.isSelected = has; sub.isSelected = has
            if (has) { glint(v); frameRow(v.parent as View) }
        }
        c.setOnClickListener {
            val i = cams.indexOf(cam)
            inFavs = shownFav
            if (i >= 0 && i != current) switchTo(i)
            closeGrid()
        }
        c.setOnLongClickListener { toggleFavorite(cams.indexOf(cam)); true }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            addView(c, LinearLayout.LayoutParams(w, h))
            addView(name, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(sub, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- location ----

    private var pickerOpen = false
    /** Where the picker is: empty at the top, then a continent, a country, a state and, where a state has them, a region. */
    private val pickPath = mutableListOf<String>()
    /**
     * The cities worth offering: only those with a live camera near them (see [Repo.places]). Empty until the cached
     * answer is read at launch, then refreshed from the network once the cameras are up.
     */
    private var places = emptyList<Place>()
    /** [places] as the picker opened with it, so a list that arrives mid-browse waits for the next open. */
    private var pickPlaces = emptyList<Place>()
    /** Cities that opened onto nothing this session; they stay out until the next launch. */
    private val emptyCities = mutableSetOf<String>()

    /** The grouping levels above the cities, tvOS Settings style. */
    private val pickLevels: List<(Place) -> String> = listOf({ p -> p.continent }, { p -> p.country }, { p -> p.state }, { p -> p.region })

    /** The viewer's own place: the chosen city, else the listed one nearest the network's guess. */
    private fun home(): Place? = CITIES.find { it.name == repo.city } ?: repo.detectedAt?.let { (lat, lon) ->
        CITIES.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) }
    }

    private fun inPath(p: Place) = pickPath.indices.all { pickLevels[it](p) == pickPath[it] }

    /** A city opened past the place levels, listing its cameras; null while choosing a place. */
    private var pickCity: Place? = null
    /** The row that opened [pickCity], for focus to return to. */
    private var pickFrom: String? = null
    /** Bumped per shown level, so a camera list that arrives late never lands on another level. */
    private var pickGen = 0
    /** The search level is open (from the top); [pickCity] may still open past it. */
    private var searching = false
    /** The recent cities level is open (from the top); [pickCity] may still open past it. */
    private var recentOpen = false

    private fun openPicker() {
        pickPath.clear(); pickPlaces = places; pickCity = null; searching = false; recentOpen = false; search.setText("")
        pickerOpen = true
        ui.removeCallbacks(idle)
        picker.animate().cancel()
        backdrop(pickBg)
        picker.visibility = View.VISIBLE; picker.alpha = .6f
        picker.fadeTo(1f, 220).start()
        pickCol.translationY = dp(24).toFloat()
        pickCol.animate().translationY(0f).setDuration(380).setInterpolator(ease).start()
        showPickLevel()
    }

    /** One level of the picker. Focus lands on [focus] (the group just left), else on the current choice. */
    private fun showPickLevel(focus: String? = null, forward: Boolean = false) {
        pickCity?.let { showCityLevel(it, forward); return }
        if (searching) { showSearchLevel(focus, forward); return }
        if (recentOpen) { showRecentLevel(focus, forward); return }
        val list = pickList
        list.removeAllViews()
        pickGen++
        val chosen = repo.city
        val chosenPlace = CITIES.find { it.name == chosen }
        // A level emptied under the viewer (its last city had nothing live) gives way to the nearest one that still has some.
        while (pickPath.isNotEmpty() && pickPlaces.none(::inPath)) pickPath.removeAt(pickPath.size - 1)
        val here = pickPlaces.filter(::inPath)
        // A level of one group (a country of one state, a state with no regions) is stepped through, not shown as a list of one.
        while (pickPath.size in 1 until pickLevels.size && here.map(pickLevels[pickPath.size]).distinct().size == 1)
            pickPath += pickLevels[pickPath.size](here[0])
        val depth = pickPath.size
        pickTitle.text = pickPath.lastOrNull { it.isNotEmpty() } ?: "Location"
        var target: View? = null
        fun row(label: String, value: String?, mark: Int?, gapAfter: Int, onClick: () -> Unit) = pickRow(label, value, mark, gapAfter, onClick)
        fun choose(name: String?) = pickChoose(name)
        val watching = cams.getOrNull(current)?.title

        var firstGroup: View? = null
        if (depth == 0) {
            val find = row("Search", null, R.drawable.ic_search, 2) { searching = true; showPickLevel(forward = true) }
            if (focus == "search") target = find
            val recent = recentPlaces()
            val auto = row("Automatic", repo.detected ?: "From your network", if (chosen == null) R.drawable.ic_check else null,
                if (recent.isEmpty()) 24 else 2) { choose(null) }
            if (chosen == null && focus == null) target = auto
            // Recently picked cities are one level in, so switching between two places is two steps, not four.
            if (recent.isNotEmpty()) {
                val open = Runnable { recentOpen = true; showPickLevel(forward = true) }
                val v = row("Recent", recent[0].name, R.drawable.ic_chevron, 24) { open.run() }.apply { tag = open }
                if (focus == "recent") target = v
            }
        }
        // The viewer's own continent leads the top level, the rest in a fixed order; a level down, their own country leads.
        val mine = home()?.let { h -> pickLevels.getOrNull(depth)?.invoke(h) }
        val rank = { k: String -> if (depth == 0) CONTINENT_ORDER.indexOf(k).let { if (it < 0) CONTINENT_ORDER.size else it } else 0 }
        val groups = (if (depth < pickLevels.size) here.groupBy(pickLevels[depth]) else emptyMap())
            .toList().sortedWith(compareBy({ it.first != mine }, { rank(it.first) })).toMap()
        // Below the top, a level where every group is a single city (Italy: Lazio, Veneto) lists the cities themselves.
        val listsGroups = groups.isNotEmpty() && (depth == 0 || groups.values.any { it.size > 1 })
        pickNote.text = when { depth == 0 -> "Cameras near this place come first."; listsGroups -> "Choose a region."; else -> "Choose a city." }
        if (listsGroups) {
            groups.keys.forEach { group ->
                val inGroup = groups.getValue(group)
                val holdsChoice = chosenPlace in inGroup
                // A group of one city is that city: it opens straight onto the city's cameras.
                val only = inGroup.singleOrNull()
                val open = if (only != null) cityOpener(only, group) else Runnable { pickPath += group; showPickLevel(forward = true) }
                // The row carries its opener so Right can go in too, the way its chevron points.
                val v = row(group, chosenPlace?.name?.takeIf { holdsChoice && it != group } ?: watching.takeIf { holdsChoice },
                    R.drawable.ic_chevron, 2) { open.run() }.apply { tag = open }
                if (group == focus || (focus == null && holdsChoice && target == null)) target = v
                if (firstGroup == null) firstGroup = v
            }
        } else {
            here.forEach { p ->
                val open = cityOpener(p, p.name)
                val v = row(p.name, watching.takeIf { p.name == chosen }, R.drawable.ic_chevron, 2) { open.run() }.apply { tag = open }
                if (p.name == focus || (focus == null && p.name == chosen)) target = v
                if (firstGroup == null) firstGroup = v
            }
        }

        pickSettle(target ?: firstGroup ?: list.getChildAt(0), forward)
    }

    /** Cities picked by hand that still have something live, latest first. */
    private fun recentPlaces() = repo.recent.mapNotNull { n -> pickPlaces.find { it.name == n } }

    /** The recent cities: Select picks one straight away, Right opens it onto its cameras. */
    private fun showRecentLevel(focus: String?, forward: Boolean) {
        val recent = recentPlaces()
        // Its last city went dark while open: the level gives way to the top.
        if (recent.isEmpty()) { recentOpen = false; showPickLevel(forward = forward); return }
        pickList.removeAllViews(); pickGen++
        pickTitle.text = "Recent"; pickNote.text = "Choose a city."
        val chosen = repo.city
        var target: View? = null
        recent.forEach { p ->
            val where = listOf(p.state, p.country).filter { it.isNotEmpty() && it != p.name }.distinct().joinToString(", ")
            val v = pickRow(p.name, where.ifEmpty { null }, if (p.name == chosen) R.drawable.ic_check else null, 2) { pickChoose(p.name) }
                .apply { tag = cityOpener(p, "recent:${p.name}") }
            if (focus == "recent:${p.name}" || (focus == null && p.name == chosen && target == null)) target = v
        }
        pickSettle(target ?: pickList.getChildAt(0), forward)
    }

    // tvOS Settings rows: flat until focused, then the bright platter with the text inverted onto it. A row may carry
    // a dim value on the right (the detected place, or the chosen city inside a group) and a check or a chevron.
    private fun pickRow(label: String, value: String?, mark: Int?, gapAfter: Int, onClick: () -> Unit): View {
        val list = pickList; val scroll = pickScroll; val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val colors = getColorStateList(R.color.on_focus_text)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(TextView(context).apply {
                text = label; textSize = 17f; setTextColor(colors); isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            if (value != null) addView(TextView(context).apply {
                text = value; textSize = 15f; isDuplicateParentStateEnabled = true
                setTextColor(getColorStateList(R.color.on_focus_dim)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(16) })
            if (mark != null) addView(ImageView(context).apply {
                setImageResource(mark); isDuplicateParentStateEnabled = true
                // The chevron points down on the location label; here it points the way Select goes.
                if (mark == R.drawable.ic_chevron) { rotation = -90f; imageTintList = getColorStateList(R.color.on_focus_dim) }
                else imageTintList = colors
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(if (value != null) 8 else 16) })
        }
        // The row itself is a frame so the focus glint can sweep it; the text and value pass its state through.
        val v = FrameLayout(this).apply {
            // Built like a poster's frame: the XML platter selector drew square here and cast a hard slab of shadow.
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); color = getColorStateList(R.color.platter_fill) }
            // An explicit outline keeps the shadow at full strength; the fill's own outline would carry its unfocused clear alpha.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
            }
            clipToOutline = true
            outlineSpotShadowColor = 0xFF000000.toInt()
            isFocusable = true; isClickable = true
            line.isDuplicateParentStateEnabled = true
            addView(line, FrameLayout.LayoutParams(MATCH_PARENT, wrap))
            // Laid out shorter than the row so it never sets the row's height; scaled to sweep past both edges.
            addView(sheen().apply { scaleY = 3f; translationX = -dp(472).toFloat() }, FrameLayout.LayoutParams(dp(236), dp(40)).apply { gravity = Gravity.CENTER_VERTICAL })
            setOnFocusChangeListener { v, has ->
                // A full-width row grows less than a poster, as in tvOS Settings: 1.04 still reads as lifted.
                // The platter clears the instant focus leaves, so the shadow goes with it: sinking slowly, it
                // showed as a dark slab under the bare text for a few frames.
                if (!has) v.translationZ = 0f
                lift(v, has, 1.04f)
                if (has) { glint(v); if (scroll.height > 0) scroll.smoothScrollTo(0, pickCenter(v)) }
            }
            setOnClickListener { onClick() }
        }
        list.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, wrap).apply { bottomMargin = dp(gapAfter) })
        return v
    }

    private fun pickCenter(v: View) = v.top + v.height / 2 - pickScroll.height / 2 + pickScroll.paddingTop

    /** Shows a freshly built level: slides it in and puts focus on [first], centered. */
    private fun pickSettle(first: View, forward: Boolean) {
        val list = pickList; val scroll = pickScroll
        // Going in slides the new level in from the right, coming back from the left, the way tvOS pushes and pops.
        if (picker.alpha == 1f) {
            list.alpha = 0f; list.translationX = dp(if (forward) 32 else -32).toFloat()
            list.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(ease).start()
        }
        // Start from the top: a scroll left over from the last visit would leave focus on a row that is off screen.
        scroll.scrollTo(0, 0)
        first.requestFocus()
        // The scroll never auto-scrolls to focus, so center the row once the new level is laid out; on a first open the
        // list has no height until then. Only for this level, and only while focus has not moved on: that layout can
        // come late (a clock tick), and must never pull focus back from where the viewer went.
        val gen = pickGen
        fun settle() {
            if (gen != pickGen || first.parent == null) return
            val at = pickList.findFocus()
            if (at != null && at !== first) return
            first.requestFocus(); scroll.scrollTo(0, pickCenter(first))
        }
        if (!scroll.isLayoutRequested && scroll.height > 0) scroll.post { settle() }
        else scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this); v.post { settle() }
            }
        })
    }

    /** A choice is done with: the picker and the options under it close onto the picture. */
    private fun pickChoose(name: String?) { closePicker(); closeActions(); if (name != repo.city) { repo.city = name; load() } }

    /** Every city opens onto its cameras; [from] names the row, so Back lands on it again. */
    private fun cityOpener(p: Place, from: String) = Runnable { hideKeys(); pickCity = p; pickFrom = from; showPickLevel(forward = true) }

    private fun hideKeys() = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)

    /** The last level: one city's live cameras, under a row that picks the city as a whole. */
    private fun showCityLevel(p: Place, forward: Boolean) {
        val list = pickList
        list.removeAllViews()
        val gen = ++pickGen
        pickTitle.text = p.name; pickNote.text = "Choose a camera."
        val here = repo.city == p.name
        val playing = cams.getOrNull(current)?.id.takeIf { here }
        val all = pickRow("All cameras", null, if (here) R.drawable.ic_check else null, 24) { pickChoose(p.name) }
        val spin = Spinner(this)
        list.addView(spin, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(20); topMargin = dp(8) })
        pickSettle(all, forward)
        Net.async({ runCatching { repo.around(p) } }) { r ->
            if (gen != pickGen || !pickerOpen) return@async
            list.removeView(spin)
            // A failed lookup must not hide a city: say so and leave it where it is.
            val found = r.getOrElse { pickNote.text = "Can’t reach the cameras. Try again in a moment."; return@async }
            // Nothing live after all (a stream that ended since the server last looked): the city leaves the picker for
            // this session, and its country with it when that was the last one, rather than stay as a dead end.
            if (found.isEmpty()) {
                emptyCities += p.name
                places = places - p; pickPlaces = pickPlaces - p
                pickBack()
                pickNote.text = "Nothing is live in ${p.name} right now."
                return@async
            }
            var now: View? = null
            found.forEach { cam ->
                val v = pickRow(cam.title, cam.quality.ifEmpty { null }, if (cam.id == playing) R.drawable.ic_check else null, 2) { pickCam(p, cam) }
                if (cam.id == playing) now = v
            }
            // Still on the first row, focus moves to the camera on screen, the way tvOS returns to where you were.
            now?.let { if (all.hasFocus()) { it.requestFocus(); pickScroll.smoothScrollTo(0, pickCenter(it)) } }
        }
    }

    /** Picks one camera: the city it belongs to, and that camera first in it. */
    private fun pickCam(p: Place, cam: Cam) {
        repo.rememberCam(p.name, cam.id)
        closePicker(); closeActions()
        if (repo.city != p.name) { repo.city = p.name; load(); return }
        val i = cams.indexOfFirst { it.id == cam.id }
        if (i < 0) { load(); return }
        inFavs = isFav(i)
        if (i != current) switchTo(i)
    }

    /**
     * The search field heads the search level and survives its rebuilds, so typing never loses focus. The TV's own
     * keyboard (with its microphone) does the typing; Done hands focus to the first result.
     */
    private val search: EditText by lazy {
        EditText(this).apply {
            hint = "City, state, country or continent"; textSize = 17f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(getColorStateList(R.color.on_focus_text)); setHintTextColor(getColorStateList(R.color.on_focus_dim))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); color = getColorStateList(R.color.platter_fill) }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnFocusChangeListener { v, has -> lift(v, has, 1.04f) }
            setOnEditorActionListener { _, _, _ ->
                hideKeys()
                pickList.getChildAt(1)?.takeIf { it.isFocusable }?.requestFocus(); true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { if (searching && pickCity == null) searchResults(null) }
            })
        }
    }

    private fun showSearchLevel(focus: String?, forward: Boolean) {
        val list = pickList
        list.removeAllViews(); pickGen++
        pickTitle.text = "Search"
        (search.parent as? ViewGroup)?.removeView(search)
        list.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(24) })
        val back = searchResults(focus)
        pickSettle(back ?: search, forward)
        // Fresh in, the keyboard comes up at once; coming back from a city, focus returns to its result instead.
        if (back == null) search.post {
            search.requestFocus(); search.setSelection(search.length())
            getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
        }
    }

    /** Rebuilds the results under the field, best match first; returns the row named [focus], if any. */
    private fun searchResults(focus: String?): View? {
        val list = pickList
        while (list.childCount > 1) list.removeViewAt(1)
        val q = search.text.toString()
        val scored = pickPlaces.map { it to it.match(q) }.filter { it.second > 0 }
        // Far weaker guesses only crowd out the good ones.
        val best = scored.maxOfOrNull { it.second } ?: 0
        val found = scored.filter { it.second * 2 >= best }.sortedByDescending { it.second }.map { it.first }
        pickNote.text = when { q.isBlank() -> "Find a city by name, or by its state, country or continent."; found.isEmpty() -> "No places match."; else -> "Choose a city." }
        var target: View? = null
        found.forEach { p ->
            val where = listOf(p.state, p.country).filter { it.isNotEmpty() && it != p.name }.distinct().joinToString(", ")
            val open = cityOpener(p, p.name)
            val v = pickRow(p.name, where.ifEmpty { null }, R.drawable.ic_chevron, 2) { open.run() }.apply { tag = open }
            if (p.name == focus) target = v
        }
        return target
    }


    /** Back goes up one shown level (stepping back over the ones passed through), and closes the picker from the top. */
    private fun pickBack() {
        if (pickCity != null) { pickCity = null; showPickLevel(focus = pickFrom); return }
        if (searching) { hideKeys(); searching = false; showPickLevel(focus = "search"); return }
        if (recentOpen) { recentOpen = false; showPickLevel(focus = "recent"); return }
        if (pickPath.isEmpty()) { closePicker(); return }
        var left = pickPath.removeAt(pickPath.size - 1)
        while (pickPath.isNotEmpty() && pickPlaces.filter(::inPath).map(pickLevels[pickPath.size]).distinct().size == 1)
            left = pickPath.removeAt(pickPath.size - 1)
        showPickLevel(focus = left)
    }

    private fun closePicker() {
        if (searching) hideKeys()
        pickerOpen = false
        // Back where it was opened from: the Location option, or the location button over the shelf.
        (pickOpener ?: city).requestFocus(); touch()
        picker.fadeTo(0f, 160).withEndAction {
            if (!pickerOpen && picker.alpha == 0f) { picker.visibility = View.GONE; pickBg.setImageDrawable(null) }
        }.start()
    }

    // ---- keys ----

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode
        if (pickerOpen) {
            // In the search field Left and Right move the caret, as typing expects.
            if (search.hasFocus() && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) return super.dispatchKeyEvent(e)
            val dir = when (code) {
                KeyEvent.KEYCODE_BACK -> { if (down && e.repeatCount == 0) pickBack(); return true }
                // Right goes in where a row's chevron points, Left comes back out; one level per press, never on repeat.
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && e.repeatCount == 0) (pickList.findFocus()?.tag as? Runnable)?.run()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down && e.repeatCount == 0 && (pickPath.isNotEmpty() || pickCity != null || searching || recentOpen)) pickBack(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> return super.dispatchKeyEvent(e)
            }
            if (down) pickList.findFocus()?.let { FocusFinder.getInstance().findNextFocus(pickList, it, dir)?.requestFocus() }
            return true
        }
        val d = when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> 1
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
            else -> 0
        }
        val toggle = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
        if (d != 0 || toggle) {
            if (down && e.repeatCount == 0) {
                touch()
                if (d != 0) step(d)
                // Play on a poster plays that camera, as in the TV app; on anything else it returns to full screen.
                else if (cams.isNotEmpty()) when {
                    gridOpen -> gridList.findFocus()?.takeIf { it.id == R.id.tileCard }?.performClick() ?: closeGrid()
                    browsing -> row.focusedChild?.performClick() ?: setBrowsing(false)
                    actionsOpen -> closeActions()
                    else -> openActions()
                }
            }
            return true
        }
        if (gridOpen) {
            val dir = when (code) {
                KeyEvent.KEYCODE_BACK -> { if (down && e.repeatCount == 0) closeGrid(back = true); return true }
                KeyEvent.KEYCODE_MENU -> { if (down && e.repeatCount == 0) closeGrid(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> return super.dispatchKeyEvent(e)
            }
            if (!down) return true
            // A held key steps one row at a time, slow enough to follow the scroll, like tvOS.
            val now = System.currentTimeMillis()
            if (e.repeatCount > 0 && now - gridMove < 220) return true
            gridMove = now
            // On the tabs: left and right switch, down drops into the top row; Back leaves.
            if (tabAll.hasFocus() || tabFav.hasFocus()) when (dir) {
                View.FOCUS_LEFT -> tabAll.requestFocus()
                View.FOCUS_RIGHT -> tabFav.requestFocus()
                // Down lands on the camera on screen when this tab has it, as tvOS returns to where you were.
                View.FOCUS_DOWN -> (gridCard(cams.getOrNull(current)) ?: cell(0))?.requestFocus()
            }
            // Search only inside the grid, so focus never slips onto the hidden shelf or chrome behind it.
            // Up from the top row goes to the open tab. The end of a row is an end: it rubber-bands rather than swap the
            // whole grid for the other tab, which only the tabs themselves do.
            else gridList.findFocus()?.let {
                val next = FocusFinder.getInstance().findNextFocus(gridList, it, dir)
                if (next != null) next.requestFocus()
                else if (e.repeatCount == 0) when (dir) {
                    View.FOCUS_UP -> openTab().requestFocus()
                    View.FOCUS_LEFT, View.FOCUS_RIGHT -> bump(if (dir == View.FOCUS_RIGHT) 1 else -1, it)
                }
            }
            return true
        }
        if (browsing) {
            touch()
            if (code == KeyEvent.KEYCODE_BACK && cams.isEmpty()) return super.dispatchKeyEvent(e)
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { if (down && e.repeatCount == 0 && cams.isNotEmpty()) setBrowsing(false); return true }
            return super.dispatchKeyEvent(e)
        }
        if (actionsOpen) {
            touch()
            when (code) {
                KeyEvent.KEYCODE_BACK -> { if (down) { if (detailOn) showDetail(false) else closeActions() }; return true }
                KeyEvent.KEYCODE_MENU -> { if (down && e.repeatCount == 0) closeActions(); return true }
                // Arrows only move along the row; Back is the way out.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (cams.isEmpty()) when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                if (down) { setBrowsing(true); city.requestFocus() }; return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { if (down && e.repeatCount == 0) setBrowsing(true) }
            // Up or Select brings up the options row: settings are one press away from the picture.
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) openActions() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && (e.repeatCount == 0 || System.currentTimeMillis() - lastSwitch >= 300))
                step(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            // Select opens actions on release; holding it favorites the camera instead.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                down && e.repeatCount == 0 -> { selectDown = true; selectLong = false }
                down && e.repeatCount == 1 && selectDown -> { selectLong = true; toggleFavorite(current) }
                !down -> { if (selectDown && !selectLong && !e.isCanceled) openActions(); selectDown = false; selectLong = false }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!infoShown) return super.dispatchKeyEvent(e)
                if (down) { ui.removeCallbacks(timeout); hideInfo.run() }
            }
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    companion object {
        /** Picture size labels, in [Pane.CAPS] order. */
        private val SIZES = arrayOf("Smaller", "Full screen")
        /** Shelf poster size in dp, 16:9. */
        private const val TILE_W = 184
        private const val TILE_H = 104
        /** Posters without focus sit a step darker, so the focused one reads as the cursor. */
        private const val POSTER_DIM = .85f
    }
}
