package dev.vr.panes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Net {
    val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun <T> async(work: () -> T, done: (T) -> Unit) {
        pool.execute { val r = runCatching(work).getOrNull(); main.post { if (r != null) done(r) } }
    }

    /** [fresh] skips the shared HTTP cache, for callers that keep their own copy and must see changes. */
    fun text(url: String, fresh: Boolean = false): String? = get(url, 15_000, fresh)?.let { String(it.first) }

    fun post(url: String, json: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 5_000; readTimeout = 8_000; doOutput = true; requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(json.toByteArray()) }
            try {
                if (responseCode == 200) inputStream.use { String(it.readBytes()) }
                else { android.util.Log.w("PanesNet", "POST $url -> $responseCode"); null }
            } finally { disconnect() }
        }
    } catch (e: Exception) { android.util.Log.w("PanesNet", "POST $url failed: $e"); null }

    /** Body plus Last-Modified (0 when absent). */
    fun get(url: String, readMs: Int, fresh: Boolean = false): Pair<ByteArray, Long>? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 5_000; readTimeout = readMs; useCaches = !fresh
            setRequestProperty("User-Agent", "Panes/1.0 (Android TV)")
            try {
                if (responseCode == 200) inputStream.use { it.readBytes() } to lastModified else null
            } finally { disconnect() }
        }
    } catch (e: Exception) { null }
}

/** A camera frame that passed the health checks, or null for dead, frozen and placeholder feeds. */
object Snap {
    private const val STALE_MS = 10 * 60_000L

    private fun url(id: String) = "https://fl511.com/map/Cctv/$id"

    class Frame(val bitmap: Bitmap, val takenAt: Long)

    /** Decoded no larger than needed to fill [width] pixels, so a 4K source never lands in memory at 4K. */
    fun fetch(cam: Cam, width: Int): Frame? {
        if (!cam.road) {
            // Scenic stills are posters, not liveness checks: the stream itself proves the camera is up.
            // A live YouTube stream has no maxres thumbnail until it has run a while; its smaller ones exist sooner.
            val tube = if (cam.tube.isEmpty()) emptyList()
                else listOf("hqdefault_live", "hqdefault").map { "https://i.ytimg.com/vi/${cam.tube}/$it.jpg" }
            for (u in listOfNotNull(cam.still.ifEmpty { null }) + tube) {
                val (bytes, _) = Net.get(u, 8_000) ?: continue
                // A missing thumbnail comes back as YouTube's 120x90 grey slate, not a 404.
                val bmp = decode(bytes, width) ?: continue
                if (bmp.width < 200 || looksBlank(bmp)) { bmp.recycle(); continue }
                return Frame(bmp, System.currentTimeMillis())
            }
            return null
        }
        val started = System.currentTimeMillis()
        val (bytes, modified) = Net.get(url(cam.id), 6_000) ?: return null
        if (bytes.size < 4_000) return null
        if (System.currentTimeMillis() - started > 6_000) return null
        // Live frames carry Last-Modified; FL511's "No live camera feed" slate does not.
        if (modified <= 0) return null
        if (System.currentTimeMillis() - modified > STALE_MS) return null
        val bmp = decode(bytes, width) ?: return null
        if (looksBlank(bmp)) { bmp.recycle(); return null }
        return Frame(bmp, modified)
    }

    private fun decode(bytes: ByteArray, width: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= width.coerceAtLeast(1)) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** "Camera unavailable" slates and dead feeds are near-uniform; real scenes are not. */
    private fun looksBlank(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 16, 9, true)
        var sum = 0.0; var sq = 0.0
        for (y in 0 until 9) for (x in 0 until 16) {
            val p = s.getPixel(x, y)
            val l = 0.299 * (p shr 16 and 255) + 0.587 * (p shr 8 and 255) + 0.114 * (p and 255)
            sum += l; sq += l * l
        }
        if (s !== b) s.recycle()
        val mean = sum / 144
        return Math.sqrt(sq / 144 - mean * mean) < 8
    }
}
