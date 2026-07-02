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
class ArtworkStore(dataDir: Path, private val http: HttpClient) {
    private val log = LoggerFactory.getLogger(ArtworkStore::class.java)
    private val json = jacksonObjectMapper()

    private val cacheDir = dataDir.resolve("cache")
    private val locks = ConcurrentHashMap<String, Any>()

    /** One artwork URL found in a feed. `channel` marks the podcast's main logo. */
    data class Entry(val url: String, val channel: Boolean = false)

    data class ArtMeta(val url: String, val contentType: String)

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
     * Returns the stamped artwork, downloading the original and applying the
     * "MIRROR" overlay on first request. If the image format can't be decoded
     * (e.g. webp), the original is cached and served unmodified.
     */
    fun getOrCreate(podcastId: String, imageId: String, entry: Entry): Artwork {
        val lock = locks.computeIfAbsent("$podcastId/$imageId") { Any() }
        synchronized(lock) {
            if (imageFile(podcastId, imageId).exists() && metaFile(podcastId, imageId).exists()) {
                val meta = json.readValue<ArtMeta>(metaFile(podcastId, imageId).toFile())
                return Artwork(Files.readAllBytes(imageFile(podcastId, imageId)), meta.contentType)
            }

            log.info("[{}] downloading artwork {} from {}", podcastId, imageId, entry.url)
            val request = HttpRequest.newBuilder(URI.create(entry.url)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() in 200..299) {
                "Origin returned HTTP ${response.statusCode()} for ${entry.url}"
            }
            val original = response.body()

            val stamped = MirrorOverlay.apply(original)
            val artwork = if (stamped != null) {
                Artwork(stamped, "image/png")
            } else {
                log.warn("[{}] could not decode artwork {} - serving it unstamped", podcastId, imageId)
                Artwork(original, response.headers().firstValue("Content-Type").orElse("image/jpeg"))
            }

            Files.createDirectories(dir(podcastId))
            Files.write(imageFile(podcastId, imageId), artwork.bytes)
            json.writeValue(metaFile(podcastId, imageId).toFile(), ArtMeta(entry.url, artwork.contentType))
            log.info("[{}] cached {} artwork {}", podcastId, if (stamped != null) "stamped" else "original", imageId)
            return artwork
        }
    }
}

/**
 * Stamps an image as a mirror copy: a blue border plus a blue band across the
 * lower part with "MIRROR" in white. All sizes scale with the image.
 */
object MirrorOverlay {
    private val BLUE = Color(21, 87, 194)
    private const val LABEL = "MIRROR"

    /** Returns the stamped image as PNG bytes, or null if the input can't be decoded. */
    fun apply(originalBytes: ByteArray): ByteArray? {
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

            g.color = BLUE
            val border = maxOf(4, minOf(width, height) / 24)
            g.fillRect(0, 0, width, border)
            g.fillRect(0, height - border, width, border)
            g.fillRect(0, 0, border, height)
            g.fillRect(width - border, 0, border, height)

            val bandHeight = maxOf(18, height / 6)
            val bandY = height * 3 / 4 - bandHeight / 2
            g.fillRect(0, bandY, width, bandHeight)

            g.color = Color.WHITE
            var fontSize = bandHeight * 0.65
            var font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
            while (fontSize > 8 && g.getFontMetrics(font).stringWidth(LABEL) > width * 0.85) {
                fontSize *= 0.9
                font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
            }
            g.font = font
            val metrics = g.fontMetrics
            g.drawString(
                LABEL,
                (width - metrics.stringWidth(LABEL)) / 2,
                bandY + (bandHeight + metrics.ascent - metrics.descent) / 2,
            )
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(canvas, "png", out)
        return out.toByteArray()
    }
}
