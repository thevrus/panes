package dev.vr.panes

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.*

class Repo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("panes", Context.MODE_PRIVATE)
    private val dir = ctx.filesDir

    /** City picked by hand, or null for automatic. */
    var city: String?
        get() = prefs.getString("city", null)
        set(v) {
            val e = prefs.edit().putString("city", v)
            if (v != null) e.putString("recent", (listOf(v) + recent.filter { it != v }).take(6).joinToString("\n"))
            e.apply()
        }

    /** Cities picked by hand, latest first, so going back to one is a single step from the top of the picker. */
    val recent: List<String> get() = prefs.getString("recent", null)?.split("\n").orEmpty()

    /** Picture size, an index into [Pane.CAPS]; Full screen unless chosen otherwise (the old Balanced and Larger included). */
    var size: Int
        get() = prefs.getInt("size", 1).coerceAtMost(1)
        set(v) = prefs.edit().putInt("size", v).apply()

    /** Hear the camera when it has sound. Off by default: this is an ambient app. */
    var sound: Boolean
        get() = prefs.getBoolean("sound", false)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    /** One-time hints: whether the viewer has already done [k]. */
    fun seen(k: String) = prefs.getBoolean("seen$k", false)
    fun see(k: String) { if (!seen(k)) prefs.edit().putBoolean("seen$k", true).apply() }

    fun rememberHeight(id: String, h: Int) { if (prefs.getInt("h:$id", 0) != h) prefs.edit().putInt("h:$id", h).apply() }

    /** A camera that died is left out for 30 min. */
    fun markDead(id: String) { prefs.edit().putLong("dead:$id", System.currentTimeMillis()).apply() }
    // Short on purpose: a YouTube stream restart or one player error should not hide a camera for long.
    private fun dead(id: String) = System.currentTimeMillis() - prefs.getLong("dead:$id", 0) < 30 * 60_000L

    fun lastCam(place: String): String? = prefs.getString("last:$place", null)
    fun rememberCam(place: String, id: String) { if (lastCam(place) != id) prefs.edit().putString("last:$place", id).apply() }

    val detected: String? get() = prefs.getString("ipCity", null)

    /** Where the network places the TV, once known. */
    val detectedAt: Pair<Double, Double>? get() = prefs.getString("ipLat", null)?.toDoubleOrNull()
        ?.let { lat -> prefs.getString("ipLon", null)?.toDoubleOrNull()?.let { lat to it } }

    val favorites: Set<String> get() = prefs.getStringSet("favs", emptySet())!!

    fun toggleFavorite(cam: Cam): Boolean {
        val now = favorites.toMutableSet()
        val added = now.add(cam.id) || run { now.remove(cam.id); false }
        val e = prefs.edit().putStringSet("favs", now)
        // A scenic favorite is kept whole so it shows up from any location; road favorites come back from the index.
        if (!cam.road) { if (added) e.putString("fav:${cam.id}", pack(cam)) else e.remove("fav:${cam.id}") }
        e.apply()
        return added
    }

    private fun pack(c: Cam) = JSONObject().put("id", c.id).put("lat", c.lat).put("lon", c.lon).put("name", c.name)
        .put("video", c.video).put("tube", c.tube).put("still", c.still).put("credit", c.credit).put("height", c.height).toString()

    private fun unpack(s: String, p: Place): Cam? = runCatching {
        val o = JSONObject(s); val lat = o.getDouble("lat"); val lon = o.getDouble("lon")
        Cam(o.getString("id"), lat, lon, km(p.lat, p.lon, lat, lon), name = o.optString("name"),
            video = o.optString("video"), videoAuth = false, tube = o.optString("tube"), still = o.optString("still"),
            credit = o.optString("credit")).apply { height = o.optInt("height") }
    }.getOrNull()

    /** Full FL511 record for one camera, cached for a day. Found through the site's text search by name. */
    fun details(cam: Cam): Map<String, String>? {
        val k = "d${cam.id}"
        prefs.getString(k, null)?.let { if (System.currentTimeMillis() - prefs.getLong("$k@", 0) < 86_400_000L) return parse(it) }
        val q = JSONObject().put("columns", JSONArray()).put("search", JSONObject().put("value", cam.title))
            .put("start", 0).put("length", 50).toString()
        val body = Net.text("https://fl511.com/List/GetData/Cameras?query=${java.net.URLEncoder.encode(q, "UTF-8")}&lang=en") ?: return null
        val rows = JSONObject(body).optJSONArray("data") ?: return null
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == cam.id } ?: return null
        prefs.edit().putString(k, row.toString()).putLong("$k@", System.currentTimeMillis()).apply()
        return parse(row.toString())
    }

    private fun parse(json: String): Map<String, String> {
        val o = JSONObject(json)
        val out = linkedMapOf<String, String>()
        fun put(label: String, v: String?) { if (!v.isNullOrBlank() && v != "null") out[label] = v }
        put("Road", o.optString("roadway"))
        put("Direction", o.optString("direction"))
        put("County", o.optString("county"))
        put("District", o.optString("dotDistrict"))
        put("Operator", o.optString("source").removePrefix("DIVAS-"))
        POINT.find(o.optJSONObject("latLng")?.optJSONObject("geography")?.optString("wellKnownText") ?: "")
            ?.let { put("Position", "%.4f, %.4f".format(it.groupValues[2].toDouble(), it.groupValues[1].toDouble())) }
        put("Camera", o.optJSONArray("images")?.optJSONObject(0)?.optString("description"))
        return out
    }

    fun place(): Place? {
        city?.let { c -> CITIES.firstOrNull { it.name == c }?.let { return it } }
        val fresh = System.currentTimeMillis() - prefs.getLong("ipAt", 0) < SIX_HOURS
        if (!fresh) Net.text("https://ipinfo.io/json")?.let {
            val j = JSONObject(it)
            val (lat, lon) = j.getString("loc").split(",")
            prefs.edit().putString("ipCity", j.optString("city")).putString("ipLat", lat)
                .putString("ipLon", lon).putLong("ipAt", System.currentTimeMillis()).apply()
        }
        val name = prefs.getString("ipCity", null) ?: return null
        return Place(name, prefs.getString("ipLat", "0")!!.toDouble(), prefs.getString("ipLon", "0")!!.toDouble())
    }

    /** What [p] has live, for the picker's camera list: no favorites from elsewhere, nothing dead. */
    fun around(p: Place): List<Cam> = (scenic(p) + roads(p, emptySet(), 30, 40.0)).filter { !dead(it.id) }

    /** Scenic cams from the server first, then the nearest road cams; favorites come along from wherever they are, for their tab. */
    fun cameras(p: Place, limit: Int = 30, maxKm: Double = 40.0): List<Cam> {
        val favs = favorites
        val near = scenic(p)
        // Keep each scenic favorite's record current while it is in reach, and bring the far ones along from it.
        val stale = near.filter { it.id in favs && prefs.getString("fav:${it.id}", null) != pack(it) }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { putString("fav:${it.id}", pack(it)) } }.apply()
        val far = favs.filter { id -> near.none { it.id == id } }.mapNotNull { id -> prefs.getString("fav:$id", null)?.let { unpack(it, p) } }
        val all = (near + far + roads(p, favs, limit, maxKm)).filter { !dead(it.id) }
        return all
    }

    /**
     * What the Panes server ranks best for this area (clean, sharp, near), cached for 6 h per half-degree
     * cell. When the server is unreachable the last good answer stands.
     */
    private fun scenic(p: Place): List<Cam> {
        if (SERVER.isEmpty()) return emptyList()
        val f = File(dir, "scenic-%.1f,%.1f.json".format(half(p.lat), half(p.lon)))
        // An empty answer is rechecked after 15 min, so a catalog that lands later reaches TVs quickly.
        val age = System.currentTimeMillis() - f.lastModified()
        val empty = f.exists() && f.length() < 512 && "\"cams\":[]" in f.readText().replace(" ", "")
        val stale = !f.exists() || age > SIX_HOURS || (empty && age > 15 * 60_000L)
        val list = cached(f, "$SERVER/v1/cams?lat=${p.lat}&lon=${p.lon}", "cams", stale) ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { o ->
            val kind = o.optString("kind")
            if (kind != "youtube" && kind != "hls") return@mapNotNull null
            // The id goes straight into the player page's markup and script.
            if (kind == "youtube" && !o.optString("videoId").matches(YT_ID)) return@mapNotNull null
            val lat = o.optDouble("lat", p.lat); val lon = o.optDouble("lon", p.lon)
            Cam(o.getString("id"), lat, lon, km(p.lat, p.lon, lat, lon), name = o.optString("title"),
                video = if (kind == "hls") o.optString("url") else "", videoAuth = false,
                // A channel id (sent for channels that restart under new ids) plays the channel's current live stream.
                tube = if (kind != "youtube") "" else o.optString("channelId").takeIf { it.matches(YT_CHANNEL) } ?: o.optString("videoId"), // Only a verified frame makes a poster; promo art reads as an ad, so those get the neutral tile.
                still = if (o.optBoolean("stillIsFrame")) o.optString("still") else "",
                credit = o.optString("attribution").ifEmpty { o.optString("channel") }).apply { height = o.optInt("height") }
        }
    }

    /**
     * Picker places with something live near them: a cell the server has a camera for, or an FL511 stream within
     * the road radius. Until the server has answered once, no place is hidden for want of scenic cameras.
     */
    fun places(roadKm: Double = 40.0, offline: Boolean = false): List<Place> {
        // No answer yet means no cells: a city shows only once something live is known to be there.
        val scenic = coverage(offline).orEmpty()
        val roads = loadIndex(offline)
        return CITIES.filter { p ->
            half(p.lat) to half(p.lon) in scenic || roads.any { km(p.lat, p.lon, it.lat, it.lon) <= roadKm }
        }
    }

    /** Cells (south-west corners) the server has a live camera for, refreshed every 6 h; null until it has answered once. */
    private fun coverage(offline: Boolean = false): Set<Pair<Double, Double>>? {
        if (SERVER.isEmpty()) return emptySet()
        val f = File(dir, "cells.json")
        val cells = cached(f, "$SERVER/v1/coverage", "cells", !offline && (!f.exists() || System.currentTimeMillis() - f.lastModified() > SIX_HOURS))
            ?: return null
        return (0 until cells.length()).mapTo(HashSet()) { cells.getJSONArray(it).let { a -> a.getDouble(0) to a.getDouble(1) } }
    }

    /** [key] of the server answer kept in [f], refreshed from [url] when [stale]; a failed or malformed answer leaves the last good copy. */
    private fun cached(f: File, url: String, key: String, stale: Boolean): JSONArray? {
        if (stale) Net.text(url, fresh = true)?.let { t ->
            runCatching { JSONObject(t).getJSONArray(key) }.getOrNull()?.let { f.writeText(t); return it }
        }
        return if (f.exists()) runCatching { JSONObject(f.readText()).getJSONArray(key) }.getOrNull() else null
    }

    /** South-west corner of the 0.5 degree cell holding [v], the server's caching unit. */
    private fun half(v: Double) = floor(v * 2) / 2

    /** Nearest FL511 road cams, favorites first. Names come from FL511 tooltips and are cached forever. */
    private fun roads(p: Place, favs: Set<String>, limit: Int, maxKm: Double): List<Cam> {
        val all = loadIndex()
        val near = all.map { it.copy(km = km(p.lat, p.lon, it.lat, it.lon)) }
            .filter { it.km <= maxKm || it.id in favs }
            .sortedBy { it.km }
        val picked = (near.filter { it.id in favs } + near.filter { it.id !in favs }.take(limit))
        val missing = picked.filter { !prefs.contains("v${it.id}") }
        if (missing.isNotEmpty()) {
            val workers = Executors.newFixedThreadPool(6)
            try {
                val edit = prefs.edit()
                workers.invokeAll(missing.map { c -> Callable { c.id to fetchMeta(c.id) } }).forEach { f ->
                    val (id, m) = f.get()
                    if (m != null) edit.putString("n$id", m.first).putString("v$id", m.second)
                }
                edit.apply()
            } finally { workers.shutdown() }
        }
        picked.forEach {
            it.name = prefs.getString("n${it.id}", "")!!
            val v = prefs.getString("v${it.id}", "")!!
            it.videoAuth = v.startsWith("auth|"); it.video = v.substringAfter("|", "")
            it.height = prefs.getInt("h:${it.id}", 0)
        }
        // Best picture first, by what was last seen playing; sorted once at load so the shelf never reshuffles.
        fun tier(c: Cam) = when { c.height >= 2160 -> 0; c.height >= 720 -> 1; c.height == 0 -> 2; else -> 3 }
        return picked.sortedWith(compareBy({ tier(it) }, { it.km }))
    }

    /** The parsed FL511 index and the file stamp it came from; callers copy, never mutate, its cams. */
    @Volatile private var index: Pair<Long, List<Cam>>? = null

    private fun loadIndex(offline: Boolean = false): List<Cam> {
        val file = File(dir, "cameras.json")
        val week = 7 * 86_400_000L
        if (!offline && (!file.exists() || System.currentTimeMillis() - file.lastModified() > week)) {
            Net.text("https://fl511.com/map/mapIcons/Cameras")?.let { file.writeText(it) }
        }
        if (!file.exists()) return emptyList()
        val stamp = file.lastModified()
        index?.let { (at, cams) -> if (at == stamp) return cams }
        val items = JSONObject(file.readText()).getJSONArray("item2")
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optJSONObject("expando")?.optBoolean("videoEnabled") == true }
            .map { o ->
                val loc = o.getJSONArray("location")
                Cam(o.getString("itemId"), loc.getDouble(0), loc.getDouble(1), 0.0)
            }.also { index = stamp to it }
    }

    /** Name plus "auth|url" / "open|url" for the HLS stream ("" when the camera has none). */
    private fun fetchMeta(id: String): Pair<String, String>? {
        val html = Net.text("https://fl511.com/tooltip/Cameras/$id?lang=en&noCss=true") ?: return null
        fun attr(re: Regex) = re.find(html)?.groupValues?.get(1)
        val name = attr(NAME)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: ""
        val url = attr(VIDEO_URL)?.takeIf { it.startsWith("http") }
        val auth = attr(VIDEO_AUTH) != "false"
        return name to (url?.let { (if (auth) "auth|" else "open|") + it } ?: "")
    }

    private fun km(a: Double, b: Double, c: Double, d: Double): Double {
        val dLat = Math.toRadians(c - a); val dLon = Math.toRadians(d - b)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dLon / 2).pow(2)
        return 12_742 * asin(sqrt(h))
    }

    companion object {
        /** Panes server base URL (`panes.server` in local.properties); empty leaves the app on road cams only. */
        const val SERVER = BuildConfig.SERVER
        private const val SIX_HOURS = 6 * 3_600_000L
        private val YT_ID = Regex("[A-Za-z0-9_-]{11}")
        private val YT_CHANNEL = Regex("UC[A-Za-z0-9_-]{22}")

        private val POINT = Regex("POINT \\(([-0-9.]+) ([-0-9.]+)\\)")
        private val NAME = Regex("CameraTooltipDescriptionColumn[^>]*>\\s*<b>([^<]+)</b>")
        private val VIDEO_URL = Regex("data-videourl=\"([^\"]+)\"")
        private val VIDEO_AUTH = Regex("data-videoauth=\"([^\"]+)\"")
    }
}
