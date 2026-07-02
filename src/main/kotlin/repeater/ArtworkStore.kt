package repeater

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.io.path.exists

/**
 * Podcast artwork, stamped so mirrored feeds are visually distinct from the
 * originals sitting next to them in a podcatcher. Same file-based layout as
 * episodes:
 *
 *   cache/<podcastId>/images.json        index: imageId -> original url
 *   cache/<podcastId>/art-<imageId>.img  the stamped image
 *   cache/<podcastId>/art-<imageId>.meta.json
 */
class ArtworkStore(dataDir: Path, private val http: HttpClient, private val transcoder: Transcoder) {
    private val log = LoggerFactory.getLogger(ArtworkStore::class.java)
    private val json = jacksonObjectMapper()

    private val cacheDir = dataDir.resolve("cache")
    private val locks = ConcurrentHashMap<String, Any>()

    /** One artwork URL found in a feed. `channel` marks the podcast's main logo. */
    data class Entry(val url: String, val channel: Boolean = false)

    // `stamp` records which overlay variant the cached image carries, so a
    // transcode config change re-stamps it instead of serving a stale look.
    data class ArtMeta(val url: String, val contentType: String, val stamp: String? = null)

    data class Artwork(val bytes: ByteArray, val contentType: String)

    private fun dir(podcastId: String) = cacheDir.resolve(podcastId)
    private fun indexFile(podcastId: String) = dir(podcastId).resolve("images.json")
    private fun imageFile(podcastId: String, imageId: String) = dir(podcastId).resolve("art-$imageId.img")
    private fun metaFile(podcastId: String, imageId: String) = dir(podcastId).resolve("art-$imageId.meta.json")

    fun updateIndex(podcastId: String, entries: Map<String, Entry>) {
        val lock = locks.computeIfAbsent("index/$podcastId") { Any() }
        synchronized(lock) {
            Files.createDirectories(dir(podcastId))
            val merged = readIndex(podcastId) + entries
            // Temp file + atomic rename so concurrent readers never see a
            // half-written index.
            val temp = indexFile(podcastId).resolveSibling("images.json.tmp")
            json.writeValue(temp.toFile(), merged)
            Files.move(temp, indexFile(podcastId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun readIndex(podcastId: String): Map<String, Entry> {
        val file = indexFile(podcastId)
        if (!file.exists()) return emptyMap()
        return json.readValue(file.toFile())
    }

    fun indexEntry(podcastId: String, imageId: String): Entry? = readIndex(podcastId)[imageId]

    /** The imageId of the podcast's main (channel-level) logo, if known. */
    fun channelImageId(podcastId: String): String? =
        readIndex(podcastId).entries.find { it.value.channel }?.key

    /**
     * The overlay this podcast's artwork should carry: red with the codec and
     * bitrate for transcoded mirrors, the plain blue "MIRROR" otherwise.
     */
    private fun stampFor(podcast: PodcastConfig): MirrorOverlay.Stamp {
        if (!transcoder.active(podcast)) {
            return MirrorOverlay.Stamp(MirrorOverlay.BLUE, listOf("MIRROR"))
        }
        val settings = transcoder.settingsFor(podcast)
        return MirrorOverlay.Stamp(
            MirrorOverlay.RED,
            listOf("MIRROR", "${settings.codec.uppercase()} ${settings.bitrateKbps}K"),
        )
    }

    /**
     * Returns the stamped artwork, downloading the original and applying the
     * overlay on first request. A cached copy whose stamp no longer matches
     * the podcast's transcode config is re-downloaded and re-stamped. If the
     * image format can't be decoded (e.g. webp), the original is cached and
     * served unmodified.
     */
    fun getOrCreate(podcast: PodcastConfig, imageId: String, entry: Entry): Artwork {
        val podcastId = podcast.id
        val stamp = stampFor(podcast)
        val lock = locks.computeIfAbsent("$podcastId/$imageId") { Any() }
        synchronized(lock) {
            if (imageFile(podcastId, imageId).exists() && metaFile(podcastId, imageId).exists()) {
                val meta = json.readValue<ArtMeta>(metaFile(podcastId, imageId).toFile())
                if (meta.stamp == stamp.key()) {
                    return Artwork(Files.readAllBytes(imageFile(podcastId, imageId)), meta.contentType)
                }
                log.info("[{}] artwork {} has an outdated stamp, re-creating", podcastId, imageId)
            }

            log.info("[{}] downloading artwork {} from {}", podcastId, imageId, entry.url)
            val request = HttpRequest.newBuilder(URI.create(entry.url)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() in 200..299) {
                "Origin returned HTTP ${response.statusCode()} for ${entry.url}"
            }
            val original = response.body()

            val stamped = MirrorOverlay.apply(original, stamp)
            val artwork = if (stamped != null) {
                Artwork(stamped, "image/png")
            } else {
                log.warn("[{}] could not decode artwork {} - serving it unstamped", podcastId, imageId)
                Artwork(original, response.headers().firstValue("Content-Type").orElse("image/jpeg"))
            }

            Files.createDirectories(dir(podcastId))
            Files.write(imageFile(podcastId, imageId), artwork.bytes)
            // The stamp key is recorded even when stamping failed, so an
            // undecodable image isn't re-downloaded on every request.
            json.writeValue(metaFile(podcastId, imageId).toFile(), ArtMeta(entry.url, artwork.contentType, stamp.key()))
            log.info("[{}] cached {} artwork {}", podcastId, if (stamped != null) "stamped" else "original", imageId)
            return artwork
        }
    }
}

/**
 * Stamps an image as a mirror copy: a colored border plus a band across the
 * lower part with one or more lines of white text. All sizes scale with the
 * image. Blue "MIRROR" for plain mirrors; red with the codec and bitrate as
 * a second line for transcoded ones.
 */
object MirrorOverlay {
    val BLUE = Color(21, 87, 194)
    val RED = Color(194, 33, 21)

    data class Stamp(val color: Color, val lines: List<String>) {
        /** Stable id stored in art meta, so config changes re-stamp cached art. */
        fun key(): String = "${color.rgb}:${lines.joinToString("|")}"
    }

    /** Returns the stamped image as PNG bytes, or null if the input can't be decoded. */
    fun apply(originalBytes: ByteArray, stamp: Stamp): ByteArray? {
        val source = try {
            ImageIO.read(ByteArrayInputStream(originalBytes))
        } catch (e: Exception) {
            null
        } ?: return null

        val width = source.width
        val height = source.height
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.drawImage(source, 0, 0, null)

            g.color = stamp.color
            val border = maxOf(4, minOf(width, height) / 24)
            g.fillRect(0, 0, width, border)
            g.fillRect(0, height - border, width, border)
            g.fillRect(0, 0, border, height)
            g.fillRect(width - border, 0, border, height)

            val lineHeight = maxOf(18, height / 6)
            val bandHeight = lineHeight * stamp.lines.size
            val bandY = height * 3 / 4 - bandHeight / 2
            g.fillRect(0, bandY, width, bandHeight)

            g.color = Color.WHITE
            stamp.lines.forEachIndexed { lineIndex, line ->
                var fontSize = lineHeight * 0.65
                var font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
                while (fontSize > 8 && g.getFontMetrics(font).stringWidth(line) > width * 0.85) {
                    fontSize *= 0.9
                    font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
                }
                g.font = font
                val metrics = g.fontMetrics
                g.drawString(
                    line,
                    (width - metrics.stringWidth(line)) / 2,
                    bandY + lineIndex * lineHeight + (lineHeight + metrics.ascent - metrics.descent) / 2,
                )
            }
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(canvas, "png", out)
        return out.toByteArray()
    }
}
